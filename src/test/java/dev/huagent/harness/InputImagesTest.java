package dev.huagent.harness;

import dev.huagent.model.ChatImage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputImagesTest {
    @TempDir
    Path root;
    private final InputImages images = new InputImages();

    @Test
    void attachesPastedImagePathFollowedByText() throws IOException {
        Path png = image("orca-paste-1.png", 3, 2, "png");

        InputImages.Result result = images.attach(png + "  这是其他agent的审查结果，你认可吗");

        assertEquals(List.of(new ChatImage("image/png", Base64.getEncoder().encodeToString(Files.readAllBytes(png)))),
                result.images());
        assertEquals(List.of("orca-paste-1.png（3×2）"), result.attached());
        assertEquals(List.of(), result.skipped());
    }

    @Test
    void findsQuotedPathsWithSpacesAndPathsTouchingChineseOrPunctuation() throws IOException {
        Path spaced = image("my shot.PNG", 4, 4, "png");
        Path jpeg = image("photo.jpg", 5, 3, "jpg");
        Path gif = image("anim.gif", 2, 2, "gif");

        InputImages.Result result = images.attach("看 \"" + spaced + "\" 和" + jpeg + "这两张，还有 " + gif + ".");

        assertEquals(List.of("image/png", "image/jpeg", "image/gif"),
                result.images().stream().map(ChatImage::mediaType).toList());
        assertEquals(List.of("my shot.PNG（4×4）", "photo.jpg（5×3）", "anim.gif（2×2）"), result.attached());
    }

    @Test
    void ignoresMissingRelativeUrlAndRepeatedPaths() throws IOException {
        Path png = image("a.png", 1, 1, "png");
        Files.copy(png, root.resolve("a.png.bak"));

        InputImages.Result result = images.attach(png + " 再贴一次 " + png + " " + root.resolve("missing.png")
                + " a.png ./a.png https://example.com/a.png " + root.resolve("a.png.bak") + " " + root);

        assertEquals(1, result.images().size(), "同一文件只附加一次，其余都不是图片路径");
        assertEquals(List.of("a.png（1×1）"), result.attached());
        assertTrue(images.attach("普通的一句话 /model 与 C:\\").isEmpty());
    }

    @Test
    void formatComesFromFileHeaderNotExtension() throws IOException {
        Path jpegNamedPng = image("really-jpeg.png", 2, 2, "jpg");
        Path text = Files.writeString(root.resolve("notes.png"), "不是图片");

        InputImages.Result result = images.attach(jpegNamedPng + " " + text);

        assertEquals(List.of("image/jpeg"), result.images().stream().map(ChatImage::mediaType).toList());
        assertEquals(List.of("notes.png：" + InputImages.UNSUPPORTED), result.skipped());
    }

    @Test
    void shrinksImagesWiderThanTheLimitKeepingAspectRatio() throws IOException {
        Path wide = image("wide.png", 4100, 50, "png");

        InputImages.Result result = images.attach(wide.toString());

        assertEquals(List.of("wide.png（4100×50，已缩小为 2000×24）"), result.attached());
        ChatImage sent = result.images().getFirst();
        assertEquals("image/png", sent.mediaType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(sent.data())));
        assertEquals(2000, decoded.getWidth());
        assertEquals(24, decoded.getHeight());
    }

    @Test
    void webpIsSentAsIsWhenSmall() throws IOException {
        byte[] header = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        Path webp = Files.write(root.resolve("s.webp"), header);

        InputImages.Result result = images.attach(webp.toString());

        assertEquals(List.of(new ChatImage("image/webp", Base64.getEncoder().encodeToString(header))), result.images());
        assertEquals(List.of("s.webp（1 KB）"), result.attached());
    }

    @Test
    void limitsImagesPerMessage() throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i <= InputImages.MAX_IMAGES; i++) {
            line.append(image("p" + i + ".png", 1, 1, "png")).append(' ');
        }

        InputImages.Result result = images.attach(line.toString());

        assertEquals(InputImages.MAX_IMAGES, result.images().size());
        assertEquals(List.of("p" + InputImages.MAX_IMAGES + ".png：每条消息最多附加 " + InputImages.MAX_IMAGES + " 张图片"),
                result.skipped());
    }

    private Path image(String name, int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            image.setRGB(x, 0, x * 0x010203);
        }
        Path path = root.resolve(name);
        assertTrue(ImageIO.write(image, format, path.toFile()));
        return path;
    }
}
