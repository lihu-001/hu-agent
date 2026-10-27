package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.PermissionGate;
import dev.huagent.permission.PermissionPolicy;
import dev.huagent.tool.file.WorkspaceFiles;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class PermissionMain {
    public static final String SYSTEM_PROMPT = ToolUseMain.SYSTEM_PROMPT
            + "写入、编辑和 Shell 调用需要程序向用户确认。权限拒绝表示工具没有执行。"
            + "尊重拒绝结果，不要换工具、改写命令或重复请求来绕过拒绝；说明结果并等待用户的新指示。";

    private PermissionMain() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        try {
            AgentConfig config = AgentConfig.load(Path.of("config.properties"));
            ModelClient client = new OpenAiClient(config.baseUrl(), config.apiKey(),
                    config.model(), Duration.ofSeconds(60));
            PermissionGate gate = new PermissionGate(
                    new PermissionPolicy(new WorkspaceFiles(config.workDirectory())), input, output);
            output.println("S03 Permission：复用 S02 工具；读取和查找直接执行，写入、编辑和 Shell 逐次确认。");
            output.println("确认时仅 y/yes 允许，其他输入或 EOF 拒绝；硬拒绝规则不能通过确认覆盖。");
            new ChatLoop(client, ToolUseMain.createTools(config, gate), config.maxModelCalls(), SYSTEM_PROMPT)
                    .run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("工作目录初始化或终端读取失败（" + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
