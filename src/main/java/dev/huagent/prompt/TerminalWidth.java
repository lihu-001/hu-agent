package dev.huagent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端当前列数。Java 17 取不到控制台窗口大小，按顺序尝试：COLUMNS 环境变量；Windows 上 mode con；
 * 其他系统上 stty size。每次调用都重新探测，窗口缩放后分隔线跟着变；探测失败一次后不再启动进程，固定返回默认值。
 */
public final class TerminalWidth implements IntSupplier {
    public static final int DEFAULT = 80;
    private static final long TIMEOUT_MILLIS = 2000;
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private final Map<String, String> environment;
    private final boolean windows;
    private boolean probeFailed;

    public TerminalWidth(Map<String, String> environment, boolean windows) {
        this.environment = environment;
        this.windows = windows;
    }

    public static TerminalWidth system() {
        return new TerminalWidth(System.getenv(), System.getProperty("os.name", "").startsWith("Windows"));
    }

    @Override
    public synchronized int getAsInt() {
        int columns = valid(environment.get("COLUMNS"));
        if (columns > 0) {
            return columns;
        }
        if (probeFailed) {
            return DEFAULT;
        }
        String text = run(windows ? List.of("cmd", "/c", "mode con")
                : List.of("sh", "-c", "stty size < /dev/tty"));
        columns = text == null ? -1 : windows ? parseModeCon(text) : parseStty(text);
        if (columns <= 0) {
            probeFailed = true;
            return DEFAULT;
        }
        return columns;
    }

    /** mode con 的输出随系统语言变化，只依赖格式：第一行以数字结尾的是行数，第二行是列数。 */
    static int parseModeCon(String text) {
        int found = 0;
        for (String line : text.lines().toList()) {
            Matcher matcher = TRAILING_NUMBER.matcher(line);
            if (matcher.find() && ++found == 2) {
                return valid(matcher.group(1));
            }
        }
        return -1;
    }

    /** stty size 输出 "行数 列数"。 */
    static int parseStty(String text) {
        String[] parts = text.strip().split("\\s+");
        return parts.length == 2 ? valid(parts[1]) : -1;
    }

    /** 过窄或过宽都视为探测错误。 */
    private static int valid(String value) {
        if (value == null || !value.strip().matches("\\d{1,4}")) {
            return -1;
        }
        int columns = Integer.parseInt(value.strip());
        return columns >= 20 && columns <= 1000 ? columns : -1;
    }

    /** 子进程的标准输入不接终端，不与读取用户输入的线程争抢；输出很短，等进程结束后再读。 */
    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            // 只需要其中的 ASCII 数字，按 ISO-8859-1 解码不受控制台代码页影响。
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return process.exitValue() == 0 ? text : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
