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
        Files.writeString(root.resolve("mvnw.cmd"), "@echo off\r\necho BUILD-LOG\r\necho BUILD-ERROR 1>&2\r\necho %*> build-args.txt\r\nexit /b " + code + "\r\n");
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
        Path output = root.resolve("output.txt");
        // 只执行 target 内复制的启动器；不调用真实安装器，也不访问网络。
        Path runner = root.resolve("run.cmd");
        Files.writeString(runner, "@echo off\r\nchcp 65001 >nul\r\ncall \"" + launcher + "\" update " + args + "\r\nexit /b %errorlevel%\r\n");
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", "call", runner.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
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
    void installerFailurePreservesExitCodeAndHidesLogs() throws Exception {
        installer("exit 7");
        assertEquals(new Result(7, "更新中\n更新失败，加 --debug 查看详细日志\n"), run("--pkg fake.zip"));
        assertEquals(new Result(7, "更新中\n更新失败，加 --debug 查看详细日志\n"), run("--local"));
    }

    @Test
    void buildFailureSkipsInstallation() throws Exception {
        build(9);
        assertEquals(new Result(9, "更新中\n更新失败，加 --debug 查看详细日志\n"), run("--local"));
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
    void quietFailureShowsOnlyTheReason() throws Exception {
        installer("throw 'INSTALL-EXCEPTION'");
        assertEquals(new Result(1, "更新中\n更新失败：INSTALL-EXCEPTION\n"), run("--pkg fake.zip"));
        Result result = run("--debug --pkg fake.zip");
        assertEquals(1, result.code());
        assertTrue(result.output().contains("INSTALL-EXCEPTION"), result.output());
        assertTrue(result.output().contains("更新失败"), result.output());
    }

    @Test
    void missingSourceTreeOrInstallerIsReported() throws Exception {
        Files.delete(root.resolve("src/dist/bin/hu.cmd"));
        assertEquals(new Result(2, "更新中\n更新失败：Run hu update --local in the hu-agent source directory.\n"),
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
        for (String args : new String[]{"--pkg", "--local extra", "--pkg a b", "--unknown", "--debug --pkg", "--local --debug"}) {
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
