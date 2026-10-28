package dev.huagent.workflow;

import dev.huagent.config.AgentConfig;
import dev.huagent.subagent.SubagentRunner;
import dev.huagent.tool.file.WorkspaceFiles;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 组装点回调：子代理运行器就绪后创建工作流运行器与工具，并打印启动修复与统计（统计只在 debug 时打印）。
 * null 表示本课机制关闭（上一课入口），组装行为与 S15 完全相同。
 */
@FunctionalInterface
public interface WorkflowStarter {

    /**
     * binding.tools 追加到主 Agent（团队模式为 Lead）的工具末尾；binding.rule 追加到系统提示词。
     * debug 为 false 时（hu 默认）不打印工作流统计（可用 /workflows 查看），启动修复与跳过的文件照常打印。
     */
    WorkflowBinding bind(SubagentRunner runner, PrintWriter output, boolean debug);

    /** 用配置中的工作目录构造；生产入口使用。工作目录初始化失败时抛出 IOException。 */
    static WorkflowStarter of(AgentConfig config) throws IOException {
        return of(new WorkspaceFiles(config.workDirectory()).root().resolve(WorkflowStore.DIRECTORY_NAME));
    }

    /** 显式存储目录，供嵌入与测试使用。 */
    static WorkflowStarter of(Path directory) {
        Objects.requireNonNull(directory);
        return (runner, output, debug) -> {
            WorkflowStore store = new WorkflowStore(directory);
            try {
                store.repairAtStartup(Instant.now(), line -> print(output, line));
                List<WorkflowRecord> workflows = store.list(line -> print(output, line));
                long completed = workflows.stream()
                        .filter(workflow -> workflow.status() == WorkflowStatus.COMPLETED).count();
                if (debug) {
                    print(output, "工作流> " + WorkflowStore.DIRECTORY_NAME + " 中有 " + workflows.size()
                            + " 个工作流，已完成 " + completed + " 个");
                }
            } catch (IOException e) {
                print(output, "工作流> 目录扫描失败（" + e.getClass().getSimpleName() + "），工作流功能仍可用");
            }
            WorkflowRunner workflowRunner = new WorkflowRunner(store, runner, Clock.systemDefaultZone(), output);
            return new WorkflowBinding(WorkflowTools.create(store, workflowRunner), WorkflowTools.RULE, store);
        };
    }

    private static void print(PrintWriter output, String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }
}
