package dev.huagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class UpdateLauncherTest {
    private Path root;
    private Path app;
    private Path launcher;

    @BeforeEach
    void setUp() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        root = Files.createTempDirectory(target, "update launcher ! ");
        app = Files.createDirectories(root.resolve("app"));
        launcher = Files.createDirectories(app.resolve("bin")).resolve("hu.cmd");
        // 与 dist.xml 的 lineEnding=dos 一致：cmd.exe 读 LF 换行的批处理会错位，不能直接用工作区里的换行。
        Files.writeString(launcher, Files.readString(Path.of("src/dist/bin/hu.cmd"))
                .replace("\r\n", "\n").replace("\n", "\r\n"));
        Path sourceLauncher = root.resolve("src/dist/bin/hu.cmd");
        Files.createDirectories(sourceLauncher.getParent());
        Files.writeString(sourceLauncher, "rem fake source marker\r\n");
        build(0);
        installer("exit 0");
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
        StringBuilder script = new StringBuilder("@echo off\r\necho BUILD-LOG\r\necho BUILD-ERROR 1>&2\r\n");
        for (String line : lines) {
            script.append("echo ").append(line).append("\r\n");
        }
        script.append("echo %*> build-args.txt\r\nexit /b ").append(code).append("\r\n");
        Files.writeString(root.resolve("mvnw.cmd"), script.toString());
    }

    private void installer(String ending) throws Exception {
        String script = "Write-Host 'INSTALL-HOST'\nWrite-Output 'INSTALL-OUTPUT'\n"
                + "Write-Warning 'INSTALL-WARNING'\n[Console]::Error.WriteLine('INSTALL-STDERR')\n"
                + "[IO.File]::WriteAllText((Join-Path $pwd 'installed.txt'), $env:HU_PACKAGE + \"`n\" + $env:HU_HOME)\n"
                + ending + "\n";
        installerScript(script);
    }

    private void installerScript(String script) throws Exception {
        Files.writeString(app.resolve("install.ps1"), script);
        Files.writeString(root.resolve("install.ps1"), script);
    }

    private Result run(String args) throws Exception {
        return run(args, root);
    }

    /** 在 directory 中运行 hu update；输出文件与运行脚本都放在 root 下。 */
    private Result run(String args, Path directory) throws Exception {
        Path output = root.resolve("output.txt");
        // 只执行 target 内复制的启动器；不调用真实安装器，也不访问网络。
        Path runner = root.resolve("run.cmd");
        Files.writeString(runner, "@echo off\r\nchcp 65001 >nul\r\ncall \"" + launcher + "\" update " + args + "\r\nexit /b %errorlevel%\r\n");
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", "call", runner.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("HU_PACKAGE");
        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "隔离启动器执行超时");
        return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void packageSuccessIsQuietAndPreservesPaths() throws Exception {
        String pkg = root.resolve("package ! with spaces.zip").toString();
        Result result = run("--pkg \"" + pkg + "\"");
        assertEquals(new Result(0, "更新中\n更新完成\n"), result);
        assertEquals(pkg + "\n" + root, Files.readString(root.resolve("installed.txt")));
    }

    @Test
    void localSuccessIsQuietAndUsesExpectedBuildArguments() throws Exception {
        assertEquals(new Result(0, "更新中\n更新完成\n"), run("--local"));
        assertEquals("-q package -DskipTests", Files.readString(root.resolve("build-args.txt")).trim());
        assertEquals(root.resolve("target/hu-agent.zip") + "\n" + root,
                Files.readString(root.resolve("installed.txt")));
    }

    @Test
    void localBuildsAndInstallsTheGivenSourceDirectoryFromAnywhere() throws Exception {
        Path elsewhere = Files.createDirectories(root.resolve("other project ! dir"));
        // 绝对路径与按当前目录解析的相对路径；构建与安装都在源码目录中进行。
        for (String args : new String[]{"--local \"" + root + "\"", "--local ..", "-d --local \"" + root + "\\\""}) {
            Files.deleteIfExists(root.resolve("installed.txt"));
            Files.deleteIfExists(root.resolve("build-args.txt"));
            Result result = run(args, elsewhere);
            assertEquals(0, result.code(), args + "：" + result.output());
            assertEquals("-q package -DskipTests", Files.readString(root.resolve("build-args.txt")).trim(), args);
            assertEquals(root.resolve("target\\hu-agent.zip") + "\n" + root,
                    Files.readString(root.resolve("installed.txt")), args);
        }
        assertFalse(Files.exists(elsewhere.resolve("build-args.txt")));
    }

    @Test
    void localWithADirectoryThatIsNotTheSourceTreeIsReported() throws Exception {
        Path elsewhere = Files.createDirectories(root.resolve("other project"));
        assertEquals(new Result(2, "更新中\n更新失败：Not a hu-agent source directory: " + elsewhere
                        + ". Run hu update --local in the source directory, or pass it: hu update --local <dir>.\n"),
                run("--local \"" + elsewhere + "\""));
        assertFalse(Files.exists(root.resolve("build-args.txt")));
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void installerFailurePreservesExitCodeAndShowsLogs() throws Exception {
        installer("exit 7");
        for (String args : new String[]{"--pkg fake.zip", "--local"}) {
            Result result = run(args);
            assertEquals(7, result.code(), args + "：" + result.output());
            assertTrue(result.output().startsWith("更新中\n"), result.output());
            // 成功时不显示的构建与安装输出，失败时全部显示，最后一行说明失败。
            for (String log : new String[]{"INSTALL-HOST", "INSTALL-OUTPUT", "INSTALL-WARNING", "INSTALL-STDERR"}) {
                assertTrue(result.output().contains(log), args + "：" + result.output());
            }
            assertEquals(args.equals("--local"), result.output().contains("BUILD-LOG"), result.output());
            assertTrue(result.output().endsWith("\n更新失败\n"), result.output());
        }
    }

    @Test
    void buildFailureShowsTheBuildLogAndSkipsInstallation() throws Exception {
        build(9);
        Result result = run("--local");
        assertEquals(9, result.code(), result.output());
        assertTrue(result.output().contains("BUILD-LOG"), result.output());
        assertTrue(result.output().contains("BUILD-ERROR"), result.output());
        assertFalse(result.output().contains("INSTALL-"), result.output());
        assertTrue(result.output().endsWith("\n更新失败\n"), result.output());
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void installerNativeStderrDoesNotFailQuietUpdate() throws Exception {
        // 与真实 install.ps1 相同：Stop 模式下运行装好的 hu.cmd --version，JVM 可能往 stderr 打印提示。
        installerScript("& {\n    $ErrorActionPreference = 'Stop'\n"
                + "    $installed = & cmd /c 'echo hu-agent 1.0& echo NOTE: Picked up JDK_JAVA_OPTIONS 1>&2'\n"
                + "    if ($LASTEXITCODE -ne 0) { throw 'hu --version failed after installation' }\n"
                + "    [IO.File]::WriteAllText((Join-Path $pwd 'installed.txt'), \"$installed\")\n}\n");
        for (String args : new String[]{"--pkg fake.zip", "--local"}) {
            Files.deleteIfExists(root.resolve("installed.txt"));
            assertEquals(new Result(0, "更新中\n更新完成\n"), run(args), args);
            assertEquals("hu-agent 1.0", Files.readString(root.resolve("installed.txt")).trim(), args);
        }
    }

    @Test
    void wrongJdkTellsToPointJavaHomeToAJdk25() throws Exception {
        String hint = "更新失败：构建需要 JDK 25：hu 自带的 Java 是 JRE，不能编译源码。请先把 JAVA_HOME 指向 JDK 25 再运行，"
                + "例如 $env:JAVA_HOME = 'C:\\path\\to\\jdk-25'\n";
        // pom 的 require-jdk-25 检查没过（JAVA_HOME 是旧 JDK），或 JAVA_HOME 指向 JRE、没有编译器。
        for (String line : new String[]{"[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin"
                + ":3.5.0:enforce (require-jdk-25) on project hu-agent:",
                "[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?"}) {
            build(1, line);
            for (String args : new String[]{"--local", "-d --local"}) {
                Result result = run(args);
                assertEquals(1, result.code(), args + "：" + result.output());
                assertTrue(result.output().contains(line), args + "：" + result.output());
                assertTrue(result.output().endsWith("\n" + hint), args + "：" + result.output());
            }
        }
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void quietFailureShowsTheLogAndThenTheReason() throws Exception {
        installer("throw 'INSTALL-EXCEPTION'");
        Result quiet = run("--pkg fake.zip");
        assertEquals(1, quiet.code(), quiet.output());
        assertTrue(quiet.output().startsWith("更新中\n"), quiet.output());
        assertTrue(quiet.output().contains("INSTALL-HOST"), quiet.output());
        assertTrue(quiet.output().endsWith("\n更新失败：INSTALL-EXCEPTION\n"), quiet.output());
        Result result = run("--debug --pkg fake.zip");
        assertEquals(1, result.code());
        assertTrue(result.output().contains("INSTALL-EXCEPTION"), result.output());
        assertTrue(result.output().contains("更新失败"), result.output());
    }

    @Test
    void missingSourceTreeOrInstallerIsReported() throws Exception {
        Files.delete(root.resolve("src/dist/bin/hu.cmd"));
        assertEquals(new Result(2, "更新中\n更新失败：Not a hu-agent source directory: " + root
                        + ". Run hu update --local in the source directory, or pass it: hu update --local <dir>.\n"),
                run("--local"));
        assertFalse(Files.exists(root.resolve("build-args.txt")));
        Files.delete(app.resolve("install.ps1"));
        assertEquals(new Result(1, "更新中\n更新失败：" + app.resolve("install.ps1")
                + " not found. Re-run the installer once to get it.\n"), run("--pkg fake.zip"));
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void bothDebugFlagsRetainBuildAndInstallerLogs() throws Exception {
        for (String args : new String[]{"--debug --local", "-d --pkg fake.zip"}) {
            Result result = run(args);
            assertEquals(0, result.code(), result.output());
            assertTrue(result.output().contains("INSTALL-HOST"), result.output());
            assertTrue(result.output().contains("INSTALL-OUTPUT"), result.output());
            assertTrue(result.output().contains("INSTALL-WARNING"), result.output());
            assertTrue(result.output().contains("INSTALL-STDERR"), result.output());
            assertTrue(result.output().contains("更新完成"), result.output());
            if (args.contains("--local")) assertTrue(result.output().contains("BUILD-LOG"), result.output());
        }
    }

    @Test
    void invalidArgumentsNeverInstall() throws Exception {
        for (String args : new String[]{"--pkg", "--local a b", "--pkg a b", "--unknown", "--debug --pkg",
                "--local --debug", "--local -x"}) {
            Result result = run(args);
            assertEquals(2, result.code(), args + ": " + result.output());
            assertTrue(result.output().contains("usage:"), result.output());
        }
        assertFalse(Files.exists(root.resolve("installed.txt")));
    }

    @Test
    void installerMayReplaceRunningLauncher() throws Exception {
        installer("[IO.File]::WriteAllText((Join-Path $env:HU_APP 'bin/hu.cmd'), '@echo REPLACED'); exit 0");
        assertEquals(new Result(0, "更新中\n更新完成\n"), run("--pkg fake.zip"));
        assertEquals("@echo REPLACED", Files.readString(launcher));
    }

    @Test
    void launcherRemainsAscii() throws Exception {
        assertTrue(Files.readString(launcher).chars().allMatch(c -> c < 128));
    }

    private record Result(int code, String output) {}
}
