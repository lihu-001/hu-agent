package dev.huagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UnixUpdateLauncherTest {
    private Path root;
    private Path app;
    private String shell;

    @BeforeEach
    void setUp() throws Exception {
        shell = System.getProperty("os.name").startsWith("Windows")
                ? "C:/Program Files/Git/bin/bash.exe" : "sh";
        try {
            Process probe = new ProcessBuilder(shell, "-c", "exit 0").start();
            boolean finished = probe.waitFor(5, TimeUnit.SECONDS);
            if (!finished) probe.destroyForcibly();
            assumeTrue(finished && probe.exitValue() == 0, "没有可用的 Unix shell");
        } catch (IOException e) {
            assumeTrue(false, "没有可用的 Unix shell：" + shell);
        }
        Path target = Files.createDirectories(Path.of("target").toAbsolutePath());
        root = Files.createTempDirectory(target, "unix update launcher ! ");
        app = Files.createDirectories(root.resolve("app"));
        Path launcher = Files.createDirectories(app.resolve("bin")).resolve("hu");
        Files.copy(Path.of("src/dist/bin/hu"), launcher);
        Path sourceLauncher = root.resolve("src/dist/bin/hu");
        Files.createDirectories(sourceLauncher.getParent());
        Files.writeString(sourceLauncher, "# 隔离源码标记\n");
        build(0);
        installer(0);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void build(int code) throws Exception {
        build(code, new String[0]);
    }

    /** 假构建器：输出 BUILD-LOG、BUILD-ERROR 与 lines（标准输出），记下参数后以 code 退出。 */
    private void build(int code, String... lines) throws Exception {
        StringBuilder script = new StringBuilder("#!/bin/sh\necho BUILD-LOG\necho BUILD-ERROR >&2\n");
        for (String line : lines) {
            script.append("echo '").append(line).append("'\n");
        }
        script.append("printf '%s\\n' \"$@\" > build-args.txt\nexit ").append(code).append("\n");
        Files.writeString(root.resolve("mvnw"), script.toString());
    }

    private void installer(int code) throws Exception {
        installer(code, "");
    }

    private void installer(int code, String ending) throws Exception {
        String script = "#!/bin/sh\necho INSTALL-LOG\necho INSTALL-ERROR >&2\n"
                + "[ \"$HU_HOME\" = \"$(pwd)\" ] || exit 81\n"
                + "printf '%s\\n' \"$HU_PACKAGE\" > installed.txt\n"
                + "if [ \"$HU_PACKAGE\" = \"$(pwd)/target/hu-agent.tar.gz\" ]; then\n"
                + "    echo local > package-kind.txt\nfi\n"
                + ending
                + "exit " + code + "\n";
        Files.writeString(app.resolve("install.sh"), script);
        Files.writeString(root.resolve("install.sh"), script);
    }

    private Result run(String... args) throws Exception {
        return runIn(root, args);
    }

    /** 在 directory 中运行 hu update；启动器用绝对路径（正斜杠，Git Bash 与 Unix 都认）。 */
    private Result runIn(Path directory, String... args) throws Exception {
        Path output = root.resolve("output.txt");
        String launcher = directory.equals(root) ? "app/bin/hu" : unix(app.resolve("bin/hu"));
        var command = new ArrayList<>(Arrays.asList(shell, launcher, "update"));
        command.addAll(Arrays.asList(args));
        // 只运行 target 中的副本和假安装器/构建器；不执行真实安装，不访问网络。
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        for (String name : new String[]{"HU_PACKAGE", "HU_HOME", "HU_BIN_DIR", "BASH_ENV", "ENV"}) {
            builder.environment().remove(name);
        }
        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(finished, "隔离启动器执行超时");
        return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void packageSuccessIsQuietAndPreservesPaths() throws Exception {
        String pkg = "package ! with spaces.tar.gz";
        assertEquals(new Result(0, "更新中\n更新完成\n"), run("--pkg", pkg));
        assertEquals(pkg + "\n", Files.readString(root.resolve("installed.txt")));
        assertFalse(Files.exists(root.resolve("build-args.txt")));
    }

    @Test
    void localSuccessIsQuietAndUsesExpectedBuildArguments() throws Exception {
        assertEquals(new Result(0, "更新中\n更新完成\n"), run("--local"));
        assertEquals("-q\npackage\n-DskipTests\n", Files.readString(root.resolve("build-args.txt")));
        assertEquals("local\n", Files.readString(root.resolve("package-kind.txt")));
    }

    @Test
    void localBuildsAndInstallsTheGivenSourceDirectoryFromAnywhere() throws Exception {
        Path elsewhere = Files.createDirectories(root.resolve("other project ! dir"));
        // 相对路径按当前目录解析；构建与安装都在源码目录中进行（假安装器要求工作目录是 HU_HOME，这里两者相同）。
        for (String source : new String[]{"..", unix(root)}) {
            Files.deleteIfExists(root.resolve("build-args.txt"));
            Files.deleteIfExists(root.resolve("package-kind.txt"));
            assertEquals(new Result(0, "更新中\n更新完成\n"), runIn(elsewhere, "--local", source), source);
            assertEquals("-q\npackage\n-DskipTests\n", Files.readString(root.resolve("build-args.txt")), source);
            assertEquals("local\n", Files.readString(root.resolve("package-kind.txt")), source);
        }
        assertFalse(Files.exists(elsewhere.resolve("build-args.txt")));
    }

    @Test
    void localWithAWrongOrMissingDirectoryIsReported() throws Exception {
        Files.createDirectories(root.resolve("other project"));
        Result wrong = run("--local", "other project");
        assertFailure(wrong, 2);
        assertTrue(wrong.output().contains("other project 不是 hu-agent 源码目录"), wrong.output());
        assertEquals(new Result(2, "更新中\n更新失败：找不到源码目录 nope\n"), run("--local", "nope"));
        assertFalse(Files.exists(root.resolve("build-args.txt")));
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void installerFailurePreservesExitCodeAndShowsLogs() throws Exception {
        installer(7);
        for (String[] args : new String[][]{{"--pkg", "fake.tar.gz"}, {"--local"}}) {
            Result result = run(args);
            assertFailure(result, 7);
            // 成功时不显示的构建与安装输出，失败时按先后全部显示，最后一行说明失败。
            assertTrue(result.output().contains("INSTALL-LOG\nINSTALL-ERROR\n"), result.output());
            assertEquals(args[0].equals("--local"), result.output().contains("BUILD-LOG\nBUILD-ERROR\n"),
                    result.output());
            assertTrue(result.output().endsWith("\n更新失败\n"), result.output());
        }
    }

    @Test
    void buildFailureShowsTheBuildLogAndSkipsInstallation() throws Exception {
        build(9);
        Result result = run("--local");
        assertFailure(result, 9);
        assertTrue(result.output().contains("BUILD-LOG\nBUILD-ERROR\n"), result.output());
        // 标准输出里的构建进度不是失败原因。
        assertTrue(result.output().endsWith("\n更新失败\n"), result.output());
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void wrongJdkTellsToPointJavaHomeToAJdk25() throws Exception {
        String hint = "构建需要 JDK 25：hu 自带的 Java 是 JRE，不能编译源码。请先把 JAVA_HOME 指向 JDK 25 再运行，"
                + "例如 export JAVA_HOME=/path/to/jdk-25";
        // pom 的 require-jdk-25 检查没过（JAVA_HOME 是旧 JDK），或 JAVA_HOME 指向 JRE、没有编译器。
        for (String line : new String[]{"[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin"
                + ":3.5.0:enforce (require-jdk-25) on project hu-agent:",
                "[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?"}) {
            build(1, line);
            Result quiet = run("--local");
            assertFailure(quiet, 1);
            assertTrue(quiet.output().endsWith("BUILD-ERROR\n" + line + "\n更新失败：" + hint + "\n"), quiet.output());
            Result debug = run("-d", "--local");
            assertFailure(debug, 1);
            assertTrue(debug.output().contains(line + "\nhu: " + hint + "\n"), debug.output());
        }
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void quietFailureShowsTheLogAndThenTheReason() throws Exception {
        // 与 install.sh 的 die 相同的格式；多条时取最后一条。
        installer(1, "printf 'hu-agent> 错误：%s\\n' EARLIER >&2\n"
                + "printf 'hu-agent> 错误：%s\\n' 'Cannot replace app' >&2\n");
        assertEquals(new Result(1, "更新中\nINSTALL-LOG\nINSTALL-ERROR\nhu-agent> 错误：EARLIER\n"
                + "hu-agent> 错误：Cannot replace app\n更新失败：Cannot replace app\n"), run("--pkg", "fake.tar.gz"));
        Result debug = run("-d", "--pkg", "fake.tar.gz");
        assertFailure(debug, 1);
        assertTrue(debug.output().contains("hu-agent> 错误：Cannot replace app\n"), debug.output());
    }

    @Test
    void missingSourceTreeOrInstallerIsReported() throws Exception {
        Files.delete(root.resolve("src/dist/bin/hu"));
        Result missing = run("--local");
        assertFailure(missing, 2);
        assertTrue(missing.output().endsWith(" 不是 hu-agent 源码目录：在源码目录中运行 hu update --local，"
                + "或用 hu update --local <源码目录> 指定\n"), missing.output());
        assertFalse(Files.exists(root.resolve("build-args.txt")));
        Files.delete(app.resolve("install.sh"));
        Result result = run("--pkg", "fake.tar.gz");
        assertFailure(result, 1);
        assertTrue(result.output().contains("更新失败：找不到 "), result.output());
        assertTrue(result.output().endsWith("/install.sh，请先重新运行一次安装命令\n"), result.output());
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void bothDebugFlagsRetainBuildAndInstallerLogs() throws Exception {
        for (String flag : new String[]{"--debug", "-d"}) {
            for (String[] args : new String[][]{{flag, "--local"}, {flag, "--pkg", "fake.tar.gz"}}) {
                Result result = run(args);
                assertEquals(0, result.code(), result.output());
                assertTrue(result.output().startsWith("更新中\n"), result.output());
                assertTrue(result.output().endsWith("更新完成\n"), result.output());
                assertTrue(result.output().contains("INSTALL-LOG\n"), result.output());
                assertTrue(result.output().contains("INSTALL-ERROR\n"), result.output());
                if (args[1].equals("--local")) {
                    assertTrue(result.output().contains("BUILD-LOG\n"), result.output());
                    assertTrue(result.output().contains("BUILD-ERROR\n"), result.output());
                }
            }
        }
    }

    @Test
    void debugFailureRetainsDiagnosticsWithoutReportingSuccess() throws Exception {
        build(9);
        Result buildResult = run("--debug", "--local");
        assertFailure(buildResult, 9);
        assertTrue(buildResult.output().contains("BUILD-ERROR"), buildResult.output());
        assertFalse(Files.exists(root.resolve("installed.txt")));
        installer(7);
        Result installResult = run("-d", "--pkg", "fake.tar.gz");
        assertFailure(installResult, 7);
        assertTrue(installResult.output().contains("INSTALL-ERROR"), installResult.output());
    }

    @Test
    void invalidArgumentsNeverBuildOrInstall() throws Exception {
        for (String[] args : new String[][]{{"--pkg"}, {"--pkg", ""}, {"--local", "a", "b"}, {"--local", ""},
                {"--pkg", "a", "b"}, {"--unknown"}, {"--debug", "--pkg"},
                {"--local", "--debug"}, {"--local", "-x"}, {"--debug", "-d", "--local"}}) {
            Result result = run(args);
            assertEquals(2, result.code(), Arrays.toString(args) + ": " + result.output());
            assertTrue(result.output().contains("用法：hu update"), result.output());
            assertFalse(result.output().contains("更新中"), result.output());
            assertFalse(result.output().contains("更新完成"), result.output());
        }
        assertFalse(Files.exists(root.resolve("installed.txt")));
        assertFalse(Files.exists(root.resolve("build-args.txt")));
    }

    /** Git Bash 与 Unix 都认的路径写法：反斜杠换成正斜杠。 */
    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void assertFailure(Result result, int code) {
        assertEquals(code, result.code(), result.output());
        assertTrue(result.output().startsWith("更新中\n"), result.output());
        assertTrue(result.output().contains("更新失败"), result.output());
        assertFalse(result.output().contains("更新完成"), result.output());
    }

    private record Result(int code, String output) {}
}
