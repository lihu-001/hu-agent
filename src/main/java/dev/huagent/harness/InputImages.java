package dev.huagent.harness;

import dev.huagent.model.ChatImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户输入里找出图片文件的绝对路径，把图片随这条消息发给模型，模型看到的是图片本身而不只是路径。
 * 终端里粘贴截图时，终端通常把图片存成临时文件、输入框里得到的是它的路径；拖入文件时得到的也是路径。
 * 路径文字仍留在消息里（模型知道是哪个文件）。只认已存在的 PNG、JPEG、GIF、WebP 文件，按文件头判断格式；
 * 与 Claude Code 相同，宽或高超过 MAX_DIMENSION、或文件超过 MAX_BYTES 时按比例缩小后发送（WebP 无法解码，过大时不附加）。
 */
public final class InputImages {
    /** 每条消息最多附加的图片数。 */
    public static final int MAX_IMAGES = 20;
    /** 发送时的最大宽高（像素），超过时按比例缩小。 */
    public static final int MAX_DIMENSION = 2000;
    /** 发送的图片最大字节数：Base64 编码后不超过 5 MB（Anthropic 接口单张图片的上限）。 */
    public static final int MAX_BYTES = 5 * 1024 * 1024 / 4 * 3;
    /** 超过这个大小的文件不读取。 */
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final String UNSUPPORTED = "不是 PNG、JPEG、GIF 或 WebP 图片";

    private static final String EXTENSION = "\\.(?:png|jpe?g|gif|webp)";
    /**
     * 双引号或单引号括起的路径（可含空格），或不带引号、以盘符或 / 开头的路径（\ 加空格表示空格，macOS 终端拖入文件时这样转义）。
     * 不带引号时前面不能紧接字母数字或 : / \ . ~（排除网址与相对路径），扩展名后面不能紧接字母数字（排除 a.png.bak），
     * 紧接中文或句末标点没关系。
     */
    private static final Pattern PATH = Pattern.compile("\"([^\"\\r\\n]+?" + EXTENSION + ")\""
            + "|'([^'\\r\\n]+?" + EXTENSION + ")'"
            + "|(?<![\\w:/\\\\.~])((?:[A-Za-z]:[\\\\/]|/)(?:\\\\ |[^\\s\"'<>|])*?" + EXTENSION + ")(?!\\w|\\.\\w)",
            Pattern.CASE_INSENSITIVE);

    /** 一行输入里的图片：images 随消息发送；attached 与 skipped 是显示给用户的说明（文件名与尺寸，没能附加的原因）。 */
    public record Result(List<ChatImage> images, List<String> attached, List<String> skipped) {
        public Result {
            images = List.copyOf(images);
            attached = List.copyOf(attached);
            skipped = List.copyOf(skipped);
        }

        public boolean isEmpty() {
            return attached.isEmpty() && skipped.isEmpty();
        }
    }

    /** 不存在的路径、相对路径与不是图片扩展名的路径都不算图片，不出现在结果里；同一文件只附加一次。 */
    public Result attach(String line) {
        List<ChatImage> images = new ArrayList<>();
        List<String> attached = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Matcher matcher = PATH.matcher(line);
        while (matcher.find()) {
            Path path = path(matcher);
            if (path == null || !seen.add(path)) {
                continue;
            }
            String name = path.getFileName().toString();
            if (images.size() >= MAX_IMAGES) {
                skipped.add(name + "：每条消息最多附加 " + MAX_IMAGES + " 张图片");
                continue;
            }
            try {
                Loaded loaded = load(path);
                images.add(loaded.image());
                attached.add(name + "（" + loaded.description() + "）");
            } catch (IOException e) {
                skipped.add(name + "：" + e.getMessage());
            }
        }
        return new Result(images, attached, skipped);
    }

    private static Path path(Matcher matcher) {
        String text = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3).startsWith("/") ? matcher.group(3).replace("\\ ", " ") : matcher.group(3);
        try {
            Path path = Path.of(text);
            return path.isAbsolute() && Files.isRegularFile(path) ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private record Loaded(ChatImage image, String description) {
    }

    /** 失败时抛出的 IOException 的消息就是显示给用户的原因。 */
    private static Loaded load(Path path) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.size(path) > MAX_FILE_BYTES ? null : Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IOException("读取失败（" + e.getClass().getSimpleName() + "）");
        }
        if (bytes == null) {
            throw new IOException("文件超过 " + MAX_FILE_BYTES / 1024 / 1024 + " MB");
        }
        String mediaType = mediaType(bytes);
        if (mediaType == null) {
            throw new IOException(UNSUPPORTED);
        }
        int[] size = dimensions(bytes);
        String original = size == null ? kilobytes(bytes.length) : size[0] + "×" + size[1];
        boolean oversized = bytes.length > MAX_BYTES
                || size != null && Math.max(size[0], size[1]) > MAX_DIMENSION;
        if (!oversized) {
            return new Loaded(new ChatImage(mediaType, Base64.getEncoder().encodeToString(bytes)), original);
        }
        BufferedImage decoded = decode(bytes);
        if (decoded == null) {
            throw new IOException("图片过大（" + original + "），且无法解码缩小");
        }
        Encoded shrunk = shrink(decoded, mediaType);
        if (shrunk == null) {
            throw new IOException("图片过大（" + original + "），缩小后仍超过 " + kilobytes(MAX_BYTES));
        }
        return new Loaded(new ChatImage(shrunk.mediaType(), Base64.getEncoder().encodeToString(shrunk.bytes())),
                original + "，已缩小为 " + shrunk.width() + "×" + shrunk.height());
    }

    /** 按文件头判断格式，不看扩展名；不是这四种格式时返回 null。 */
    static String mediaType(byte[] bytes) {
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 0, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, int... prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[offset + i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 只读文件头里的宽高，不解码像素；没有能读这种格式的解码器（如 WebP）或读取失败时返回 null。 */
    private static int[] dimensions(byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(input);
            if (reader == null) {
                return null;
            }
            try {
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 解码第一帧（GIF 动图只取第一帧）。很大的图片先隔行隔列读取到目标尺寸两倍左右，避免整张解码占满内存，
     * 再由 scale 平滑缩小。无法解码时返回 null。
     */
    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(input);
            if (reader == null) {
                return null;
            }
            try {
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(reader.getWidth(0), reader.getHeight(0)) / (2 * MAX_DIMENSION);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ImageReader reader(ImageInputStream input) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        reader.setInput(input, true, true);
        return reader;
    }

    private record Encoded(String mediaType, byte[] bytes, int width, int height) {
    }

    /**
     * 按比例缩小到 MAX_DIMENSION 以内再重新编码：JPEG 仍编码为 JPEG，其余先试 PNG（截图里的文字更清楚），
     * 超过 MAX_BYTES 时改用 JPEG；仍然超过时再缩小，最多试四次，都不行时返回 null。
     */
    private static Encoded shrink(BufferedImage image, String mediaType) throws IOException {
        double factor = Math.min(1.0, (double) MAX_DIMENSION / Math.max(image.getWidth(), image.getHeight()));
        for (int attempt = 0; attempt < 4; attempt++, factor *= 0.75) {
            if (!"image/jpeg".equals(mediaType)) {
                BufferedImage scaled = scale(image, factor, image.getColorModel().hasAlpha());
                byte[] png = encode(scaled, "png");
                if (png.length <= MAX_BYTES) {
                    return new Encoded("image/png", png, scaled.getWidth(), scaled.getHeight());
                }
            }
            BufferedImage scaled = scale(image, factor, false);
            byte[] jpeg = encode(scaled, "jpeg");
            if (jpeg.length <= MAX_BYTES) {
                return new Encoded("image/jpeg", jpeg, scaled.getWidth(), scaled.getHeight());
            }
        }
        return null;
    }

    /** 缩小到原来的 factor 倍；缩小一半以上时逐次减半，比一步缩到位清楚。不保留透明时透明处填白色（JPEG 没有透明通道）。 */
    private static BufferedImage scale(BufferedImage image, double factor, boolean alpha) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2, alpha);
        }
        return current.getWidth() == width && current.getHeight() == height && current != image ? current
                : draw(current, width, height, alpha);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height, boolean alpha) {
        BufferedImage result = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            if (!alpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, format, output)) {
                throw new IOException("无法编码为 " + format);
            }
        }
        return bytes.toByteArray();
    }

    private static String kilobytes(long bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1024.0 / 1024.0)
                : Math.max(1, bytes / 1024) + " KB";
    }
}
