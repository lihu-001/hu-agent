package dev.huagent.workflow;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import dev.huagent.io.AtomicFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 持久化工作流：工作目录 .workflows/ 下每个工作流一个 workflow_xxxxxxxx.json 文件，文件是唯一数据源。
 * 每次操作重新读取；创建与修改先写临时文件再原子替换，读取时不跟随链接，超过 64 KiB 或字段非法只告警跳过。
 * 进程退出可能留下 running 状态，repairAtStartup 把它修复为 paused，未完成的步骤回到 pending。
 */
public final class WorkflowStore {
    public static final String DIRECTORY_NAME = ".workflows";
    public static final int MAX_WORKFLOWS = 20;
    public static final int MAX_NAME_CHARS = 100;
    public static final int MAX_STEPS = 20;
    public static final int MAX_PROMPT_CHARS = 4000;
    public static final int MAX_RESULT_CHARS = 12000;
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern ID = Pattern.compile("workflow_[0-9a-f]{8}");
    private static final Set<String> FIELDS = Set.of("id", "name", "status", "steps",
            "createdAt", "updatedAt", "lastError");
    private static final Set<String> STEP_FIELDS = Set.of("prompt", "status", "result",
            "startedAt", "finishedAt");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;

    public WorkflowStore(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /** 新建 pending 工作流：name 与每步 prompt 立即校验；ID 随机生成，冲突时重试。 */
    public WorkflowRecord create(String name, List<String> prompts, Instant now) throws IOException {
        String cleanName = validateName(name);
        List<WorkflowStep> steps = validatePrompts(prompts);
        Files.createDirectories(directory);
        if (list(warning -> { }).size() >= MAX_WORKFLOWS) {
            throw new IllegalArgumentException("工作流已达到 " + MAX_WORKFLOWS + " 个上限，请取消不再需要的工作流");
        }
        byte[] random = new byte[4];
        for (int attempt = 0; attempt < 100; attempt++) {
            RANDOM.nextBytes(random);
            WorkflowRecord workflow = new WorkflowRecord("workflow_" + HexFormat.of().formatHex(random),
                    cleanName, WorkflowStatus.PENDING, steps, now, now, null);
            try {
                Files.writeString(fileOf(workflow.id()), toJson(workflow), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return workflow;
            } catch (FileAlreadyExistsException e) {
                // 极少发生：换一个 ID 再试。
            }
        }
        throw new IOException("无法分配不重复的工作流 ID");
    }

    public WorkflowRecord load(String id) throws IOException {
        validateId(id);
        Path file = fileOf(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有 ID 为 " + id + " 的工作流");
        }
        return parse(file, id);
    }

    /** 按 ID 排序列出全部工作流；单个文件无效只告警跳过，目录不存在视为没有工作流。 */
    public List<WorkflowRecord> list(Consumer<String> warnings) throws IOException {
        List<WorkflowRecord> workflows = new ArrayList<>();
        for (Path file : workflowFiles()) {
            String fileName = file.getFileName().toString();
            try {
                workflows.add(parse(file, fileName.substring(0, fileName.length() - ".json".length())));
            } catch (IllegalArgumentException | IOException e) {
                warnings.accept("工作流> 跳过 " + fileName + "：" + message(e));
            }
        }
        return workflows;
    }

    /** 运行时与工具修改工作流时整体覆盖；先写临时文件再原子替换。 */
    public void save(WorkflowRecord workflow) throws IOException {
        Objects.requireNonNull(workflow);
        validateId(workflow.id());
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, workflow.id() + "-", ".tmp");
        try {
            Files.writeString(temp, toJson(workflow), StandardCharsets.UTF_8);
            AtomicFiles.replace(temp, fileOf(workflow.id()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 启动时修复上次进程退出的遗留状态：running 修复为 paused，其中 running 的步骤回到 pending。
     * 修复经临时文件替换写回；返回修复的工作流数，每条通过 warnings 输出一行说明。
     */
    public int repairAtStartup(Instant now, Consumer<String> warnings) throws IOException {
        int repaired = 0;
        for (WorkflowRecord workflow : list(warnings)) {
            if (workflow.status() != WorkflowStatus.RUNNING) {
                continue;
            }
            List<WorkflowStep> steps = new ArrayList<>();
            boolean stepReset = false;
            for (WorkflowStep step : workflow.steps()) {
                if (step.status() == StepStatus.RUNNING) {
                    steps.add(step.reset());
                    stepReset = true;
                } else {
                    steps.add(step);
                }
            }
            WorkflowRecord fixed = new WorkflowRecord(workflow.id(), workflow.name(), WorkflowStatus.PAUSED,
                    steps, workflow.createdAt(), now, workflow.lastError());
            save(fixed);
            repaired++;
            warnings.accept("工作流> " + workflow.id() + "（" + workflow.name() + "）上次执行未结束，已修复为 paused"
                    + (stepReset ? "，未完成的步骤回到 pending，重跑会重新执行" : "，可继续运行"));
        }
        return repaired;
    }

    public static String toJson(WorkflowRecord workflow) {
        JSONObject json = new JSONObject(new LinkedHashMap<>());
        json.put("id", workflow.id());
        json.put("name", workflow.name());
        json.put("status", workflow.status().wireName());
        JSONArray steps = new JSONArray();
        for (WorkflowStep step : workflow.steps()) {
            JSONObject stepJson = new JSONObject(new LinkedHashMap<>());
            stepJson.put("prompt", step.prompt());
            stepJson.put("status", step.status().wireName());
            stepJson.put("result", step.result());
            stepJson.put("startedAt", step.startedAt() == null ? null : step.startedAt().toString());
            stepJson.put("finishedAt", step.finishedAt() == null ? null : step.finishedAt().toString());
            steps.add(stepJson);
        }
        json.put("steps", steps);
        json.put("createdAt", workflow.createdAt().toString());
        json.put("updatedAt", workflow.updatedAt().toString());
        json.put("lastError", workflow.lastError());
        return JSON.toJSONString(json, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls) + "\n";
    }

    /** 一行一个工作流：状态、进度、名称与最近错误。 */
    public static String render(List<WorkflowRecord> workflows) {
        if (workflows.isEmpty()) {
            return "没有工作流。用 workflow_create 创建。";
        }
        StringBuilder text = new StringBuilder();
        for (WorkflowRecord workflow : workflows) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(workflow.status().marker()).append(' ').append(workflow.id()).append("：")
                    .append(workflow.name()).append(" | 状态=").append(workflow.status().wireName())
                    .append(" | 进度=").append(workflow.completedCount()).append('/').append(workflow.steps().size());
            if (workflow.lastError() != null) {
                text.append(" | 最近错误=").append(firstLine(workflow.lastError()));
            }
        }
        return text.toString();
    }

    public static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("工作流 ID 必须形如 workflow_ 加 8 位小写十六进制，例如 workflow_0a1b2c3d");
        }
    }

    public static String validateName(String name) {
        if (StrUtil.isBlank(name) || name.strip().length() > MAX_NAME_CHARS
                || name.strip().contains("\n") || name.strip().contains("\r")) {
            throw new IllegalArgumentException("name 必须是非空单行文字，最多 " + MAX_NAME_CHARS + " 字符");
        }
        return name.strip();
    }

    /** 校验并构造初始步骤列表：非空、数量与长度受限。 */
    public static List<WorkflowStep> validatePrompts(List<String> prompts) {
        if (prompts == null || prompts.isEmpty()) {
            throw new IllegalArgumentException("steps 必须是非空数组，至少一步");
        }
        if (prompts.size() > MAX_STEPS) {
            throw new IllegalArgumentException("步骤最多 " + MAX_STEPS + " 步");
        }
        List<WorkflowStep> steps = new ArrayList<>();
        for (String prompt : prompts) {
            if (StrUtil.isBlank(prompt) || prompt.strip().length() > MAX_PROMPT_CHARS) {
                throw new IllegalArgumentException("每步 prompt 必须是非空文字，最多 " + MAX_PROMPT_CHARS + " 字符");
            }
            steps.add(WorkflowStep.pending(prompt.strip()));
        }
        return List.copyOf(steps);
    }

    private List<Path> workflowFiles() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("工作流路径不是目录：" + directory);
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "workflow_*.json")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(null);
        return files;
    }

    private Path fileOf(String id) {
        return directory.resolve(id + ".json");
    }

    private WorkflowRecord parse(Path file, String expectedId) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("工作流 " + expectedId + " 不是普通文件");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("工作流 " + expectedId + " 文件已被删除");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 64 KiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("不是有效的 UTF-8");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是有效的 JSON 对象");
        }
        if (json == null || !FIELDS.containsAll(json.keySet())) {
            throw new IllegalArgumentException("只能包含 " + String.join("、", FIELDS.stream().sorted().toList()));
        }
        if (!expectedId.equals(json.get("id"))) {
            throw new IllegalArgumentException("id 必须与文件名一致");
        }
        if (!(json.get("name") instanceof String name) || !(json.get("status") instanceof String statusText)
                || !(json.get("steps") instanceof JSONArray stepArray)) {
            throw new IllegalArgumentException("name、status、steps 字段类型错误");
        }
        String cleanName = validateName(name);
        WorkflowStatus status = WorkflowStatus.parse(requireString(json.get("status"), "status"));
        Instant createdAt = instant(json.get("createdAt"), "createdAt");
        Instant updatedAt = instant(json.get("updatedAt"), "updatedAt");
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("createdAt 与 updatedAt 不能为空");
        }
        if (stepArray.isEmpty() || stepArray.size() > MAX_STEPS) {
            throw new IllegalArgumentException("steps 必须是 1—" + MAX_STEPS + " 步的数组");
        }
        List<WorkflowStep> steps = new ArrayList<>();
        for (Object element : stepArray) {
            steps.add(step(element));
        }
        String lastError = json.get("lastError") == null ? null
                : requireString(json.get("lastError"), "lastError");
        // 与任务状态一致的组合校验：pending 全部未开始；completed 全部完成且无错误；failed 必须带错误。
        if (status == WorkflowStatus.PENDING && steps.stream().anyMatch(step -> step.status() != StepStatus.PENDING)) {
            throw new IllegalArgumentException("pending 工作流不能有已开始的步骤");
        }
        if (status == WorkflowStatus.COMPLETED
                && steps.stream().anyMatch(step -> step.status() != StepStatus.COMPLETED)) {
            throw new IllegalArgumentException("completed 工作流的步骤必须全部完成");
        }
        if (status == WorkflowStatus.COMPLETED && lastError != null) {
            throw new IllegalArgumentException("completed 工作流不能有 lastError");
        }
        if (status == WorkflowStatus.FAILED && lastError == null) {
            throw new IllegalArgumentException("failed 工作流必须有 lastError");
        }
        return new WorkflowRecord(expectedId, cleanName, status, steps, createdAt, updatedAt, lastError);
    }

    private static WorkflowStep step(Object element) {
        if (!(element instanceof JSONObject json) || !STEP_FIELDS.containsAll(json.keySet())) {
            throw new IllegalArgumentException("步骤只能包含 "
                    + String.join("、", STEP_FIELDS.stream().sorted().toList()));
        }
        if (!(json.get("prompt") instanceof String prompt) || !(json.get("status") instanceof String statusText)) {
            throw new IllegalArgumentException("步骤 prompt、status 字段类型错误");
        }
        StepStatus status = StepStatus.parse(requireString(json.get("status"), "步骤 status"));
        String cleanPrompt = prompt.strip();
        if (cleanPrompt.isEmpty() || cleanPrompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("步骤 prompt 必须是非空文字，最多 " + MAX_PROMPT_CHARS + " 字符");
        }
        String result = json.get("result") == null ? null : requireString(json.get("result"), "result");
        if (result != null && result.length() > MAX_RESULT_CHARS) {
            throw new IllegalArgumentException("result 最多 " + MAX_RESULT_CHARS + " 字符");
        }
        Instant startedAt = instant(json.get("startedAt"), "startedAt");
        Instant finishedAt = instant(json.get("finishedAt"), "finishedAt");
        if (status == StepStatus.PENDING && (result != null || startedAt != null || finishedAt != null)) {
            throw new IllegalArgumentException("pending 步骤不能有 result、startedAt 或 finishedAt");
        }
        if (status == StepStatus.COMPLETED && result == null) {
            throw new IllegalArgumentException("completed 步骤必须有 result");
        }
        if (status == StepStatus.RUNNING && finishedAt != null) {
            throw new IllegalArgumentException("running 步骤不能有 finishedAt");
        }
        if (status != StepStatus.PENDING && startedAt == null) {
            throw new IllegalArgumentException("已开始的步骤必须有 startedAt");
        }
        if ((status == StepStatus.COMPLETED || status == StepStatus.FAILED) && finishedAt == null) {
            throw new IllegalArgumentException("结束的步骤必须有 finishedAt");
        }
        return new WorkflowStep(cleanPrompt, status, result, startedAt, finishedAt);
    }

    private static Instant instant(Object value, String field) {
        if (value == null) {
            return null;
        }
        String text = requireString(value, field);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 必须是 ISO-8601 时间");
        }
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        return text;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    private static String message(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage()
                : "读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
