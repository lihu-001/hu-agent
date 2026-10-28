package dev.huagent.prompt;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * hu 交互会话的启动页：左边是渐变色像素字 "hu"，右边是名称与版本，下面一行随机提示，两块各自在终端里水平居中。
 * 只在有 Console 时显示，输入输出被重定向时 HuMain 仍只打印一行版本信息。
 * 颜色只在确认终端能解释 ANSI 转义时输出（见 ColorSupport.detect），否则输出同样的版面、不带颜色；
 * 终端放不下像素字时退回一行 "hu-agent v版本"。
 */
public final class Splash {
    /** "#" 是一个像素，画成两列宽的方块，接近正方形。 */
    private static final String[] LOGO = {
            "#      ",
            "#      ",
            "### # #",
            "# # # #",
            "# # # #",
            "# # ###",
    };
    private static final String NAME = "hu-agent";
    /** 名称写在像素字的第几行，版本在下一行。 */
    private static final int NAME_ROW = 2;
    private static final int GAP = 4;
    private static final String TIP_LABEL = "提示：";

    /** 像素字渐变：左上深橙，经琥珀色，到右下金黄。 */
    private static final int[] GRADIENT = {0xEA580C, 0xF59E0B, 0xFACC15};
    private static final int GRAY = 0x8B949E;
    private static final int AMBER = 0xF59E0B;

    static final List<String> TIPS = List.of(
            "/goal <完成条件> 设定目标，未达成时自动续跑",
            "/compact 立即压缩上下文，可以附上需要特别保留的内容",
            "/context 查看上下文用量与自动压缩阈值",
            "/status 查看模型、工作目录、各项上限与已启用的机制",
            "/clear 清空对话，清空前完整记录写入 .transcripts",
            "/export 把当前完整对话导出为 JSONL",
            "在 .agents/skills/<名称>/SKILL.md 写技能，用 /<名称> 直接调用",
            "在 .agents/mcp.json 配置 MCP server，首次使用时需确认授权",
            "在 .agents/hook.json 配置命令 Hook，内容变化时会重新请求授权",
            "hu --debug 启动可查看每次模型请求、耗时与完整工具结果",
            "hu update 升级到最新发布版，配置与授权记录都会保留",
            "环境变量 HU_MODEL、HU_API_KEY、HU_BASE_URL 优先于配置文件");

    private Splash() {
    }

    /** 终端能输出到什么程度的颜色。 */
    public enum ColorSupport {
        NONE, PALETTE_256, TRUECOLOR;

        /**
         * 按环境变量判断：设置了 NO_COLOR 或 TERM=dumb 时不用颜色。Windows 上 Java 不会开启控制台的 ANSI 处理，
         * 只有 Windows Terminal（WT_SESSION）、VS Code 等基于 ConPTY 的终端（TERM_PROGRAM）和 ConEmu 默认解释转义，
         * 传统控制台窗口会把转义序列原样显示出来；其他系统上有 TERM 就用。
         * COLORTERM 为 truecolor / 24bit 或 Windows Terminal 时用 24 位色，否则用 256 色。
         */
        public static ColorSupport detect(Map<String, String> environment, boolean windows) {
            String noColor = environment.get("NO_COLOR");
            String term = environment.getOrDefault("TERM", "");
            if (noColor != null && !noColor.isEmpty() || term.equals("dumb")) {
                return NONE;
            }
            boolean ansi = windows
                    ? environment.containsKey("WT_SESSION") || environment.containsKey("TERM_PROGRAM")
                    || "ON".equalsIgnoreCase(environment.get("ConEmuANSI"))
                    : !term.isEmpty();
            if (!ansi) {
                return NONE;
            }
            String colorTerm = environment.getOrDefault("COLORTERM", "").toLowerCase(Locale.ROOT);
            return colorTerm.equals("truecolor") || colorTerm.equals("24bit")
                    || windows && environment.containsKey("WT_SESSION") ? TRUECOLOR : PALETTE_256;
        }

        /** 按当前进程的环境变量与操作系统判断。 */
        public static ColorSupport system() {
            return detect(System.getenv(), System.getProperty("os.name", "").startsWith("Windows"));
        }
    }

    /**
     * 按终端当前环境画启动页，提示随机选一条。能编码 ❯ 的是 Unicode 字符集，像素画成 "██"；
     * GBK 等也能编码 █，但传统控制台把它按两列显示、像素字会错位，只有能解释 ANSI 的新终端（按一列显示）里才用，
     * 其余情况画成 "##"。
     */
    public static String forTerminal(Charset charset, int columns, String version) {
        ColorSupport color = ColorSupport.system();
        CharsetEncoder encoder = charset.newEncoder();
        boolean blocks = encoder.canEncode('█') && (encoder.canEncode('❯') || color != ColorSupport.NONE);
        // 不用 RandomGenerator.getDefault()：安装的 Temurin JRE 17.0.20.1 上它找不到默认算法 L32X64MixRandom，直接抛异常。
        return render(version, tip(ThreadLocalRandom.current()), columns, blocks, color);
    }

    static String tip(RandomGenerator random) {
        return TIPS.get(random.nextInt(TIPS.size()));
    }

    /** 前后各空一行；与 FramedPrompt 一样按比列数少一列的宽度排版，避免写满一行时部分终端再多换一行。 */
    static String render(String version, String tip, int columns, boolean blocks, ColorSupport color) {
        int width = columns - 1;
        String label = version.equals("dev") ? version : "v" + version;
        int logoWidth = LOGO[0].length() * 2;
        int blockWidth = logoWidth + GAP + Math.max(NAME.length(), label.length());
        StringBuilder text = new StringBuilder("\n");
        if (blockWidth <= width) {
            String pad = " ".repeat((width - blockWidth) / 2);
            for (int row = 0; row < LOGO.length; row++) {
                String side = row == NAME_ROW ? paint(color, "1", NAME)
                        : row == NAME_ROW + 1 ? paint(color, foreground(color, GRAY), label) : "";
                String logo = logoRow(row, blocks, color);
                text.append(pad).append(side.isEmpty() ? logo.stripTrailing()
                        : logo + " ".repeat(GAP) + side).append('\n');
            }
        } else {
            text.append(paint(color, "1", NAME)).append(' ')
                    .append(paint(color, foreground(color, GRAY), label)).append('\n');
        }
        int tipWidth = FramedPrompt.displayWidth(TIP_LABEL + tip);
        text.append('\n').append(" ".repeat(tipWidth <= width ? (width - tipWidth) / 2 : 0))
                .append(paint(color, "1;3;" + foreground(color, AMBER), TIP_LABEL))
                .append(paint(color, "3", tip)).append("\n\n");
        return text.toString();
    }

    /** 空白像素也占两列，保证各行对齐；不带颜色时末尾的空白由调用方去掉。 */
    private static String logoRow(int row, boolean blocks, ColorSupport color) {
        String line = LOGO[row];
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < line.length(); column++) {
            if (line.charAt(column) != '#') {
                text.append("  ");
                continue;
            }
            // 斜向渐变：行占大部分权重，列让同一行也有些变化。
            double t = 0.75 * row / (LOGO.length - 1) + 0.25 * column / (line.length() - 1);
            text.append(paint(color, foreground(color, gradient(t)), blocks ? "██" : "##"));
        }
        return text.toString();
    }

    static int gradient(double t) {
        double scaled = Math.min(Math.max(t, 0), 1) * (GRADIENT.length - 1);
        int index = Math.min((int) scaled, GRADIENT.length - 2);
        double f = scaled - index;
        int from = GRADIENT[index];
        int to = GRADIENT[index + 1];
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = from >> shift & 0xFF;
            int b = to >> shift & 0xFF;
            rgb |= (int) Math.round(a + (b - a) * f) << shift;
        }
        return rgb;
    }

    private static String foreground(ColorSupport color, int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return color == ColorSupport.TRUECOLOR ? "38;2;" + r + ";" + g + ";" + b
                : "38;5;" + (16 + 36 * cube(r) + 6 * cube(g) + cube(b));
    }

    /** 256 色里 6×6×6 色块的通道级别：0、95、135、175、215、255，取最接近的一级。 */
    private static int cube(int value) {
        return value < 48 ? 0 : value < 115 ? 1 : (value - 35) / 40;
    }

    /** 每段文字自带重置，折行或中途中断都不会把颜色带到后面的输出。 */
    private static String paint(ColorSupport color, String sgr, String text) {
        return color == ColorSupport.NONE ? text : "\u001b[" + sgr + "m" + text + "\u001b[0m";
    }
}
