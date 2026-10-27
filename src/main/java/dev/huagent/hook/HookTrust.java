package dev.huagent.hook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 用户 Hook 启用授权，与 S03 的逐次工具审批相互独立。 */
public final class HookTrust {
    private HookTrust() {
    }

    public static List<AgentHook> load(Path projectDirectory, BufferedReader input,
                                       PrintWriter output, HookTrustStore store) throws IOException {
        HookConfig.Configuration configuration = HookConfig.read(projectDirectory);
        if (configuration.definitions().isEmpty()) {
            return List.of();
        }
        HookTrustSnapshot snapshot;
        try {
            snapshot = HookTrustSnapshot.capture(configuration);
        } catch (IOException | RuntimeException e) {
            say(output, "Hook 信任> 无法校验配置或脚本文件，用户 Hook 未启用，请检查路径和读取权限。");
            return List.of();
        }
        boolean trusted = false;
        try {
            trusted = store.isTrusted(snapshot);
        } catch (IOException e) {
            say(output, "Hook 信任> 无法读取信任记录，需要重新确认。");
        }
        if (!trusted) {
            say(output, "Hook 信任确认> 首次启用或配置/脚本已变化。");
            say(output, "配置> " + snapshot.project().resolve(HookConfig.DEFAULT_PATH));
            say(output, "命令工作目录> " + snapshot.project());
            for (HookConfig.Definition definition : configuration.definitions()) {
                say(output, "  " + display(definition.event()) + " [" + display(definition.matcher()) + "] "
                        + display(definition.command()) + "（超时 " + definition.timeout().toSeconds() + " 秒）");
            }
            for (Path file : snapshot.files()) {
                say(output, "校验文件> " + display(file.toString()));
            }
            say(output, "命令以当前用户权限运行，可读取对话、访问文件和网络；此授权不是安全审计或沙箱。");
            say(output, "只校验配置、字面量脚本和 trust_files；动态加载的其他代码不自动跟踪。");
            output.print("启用用户 Hook？[y=信任并记住 / once=仅本次 / N=不启用] ");
            output.flush();
            String answer = input.readLine();
            String choice = answer == null ? "" : answer.strip();
            boolean remember = choice.equalsIgnoreCase("y") || choice.equalsIgnoreCase("yes");
            if (!remember && !choice.equalsIgnoreCase("once")) {
                say(output, "Hook 信任> 本次未启用用户 Hook；Agent 内置 Java Hook 继续执行。");
                return List.of();
            }
            // 不允许确认过程中替换文件后使用刚才的授权。
            try {
                snapshot.verify();
            } catch (IOException e) {
                say(output, "Hook 信任> 确认期间配置或脚本发生变化，用户 Hook 未启用，请重启后重试。");
                return List.of();
            }
            if (remember) {
                try {
                    store.remember(snapshot);
                    say(output, "Hook 信任> 已保存授权：" + store.recordFile(snapshot.project()));
                } catch (IOException e) {
                    say(output, "Hook 信任> 授权无法保存，仅本次启用；下次启动仍需确认。");
                }
            }
        }
        Consumer<String> messages = message -> say(output, message);
        say(output, "Hook 信任> 已启用 " + configuration.definitions().size() + " 个用户命令 Hook。");
        return configuration.callbacks(messages, snapshot);
    }

    private static void say(PrintWriter output, String text) {
        output.println(text);
        output.flush();
    }

    private static String display(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(code -> {
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) {
                result.append(String.format("\\u%04x", code));
            } else {
                result.appendCodePoint(code);
            }
        });
        return result.toString();
    }
}
