package dev.huagent.schedule;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
 * 持久化定时计划：工作目录 .schedules/ 下每个计划一个 schedule_xxxxxxxx.json 文件，文件是唯一数据源。
 * 每次操作重新读取；创建先写临时文件再替换，读取时不跟随链接，超过 64 KiB 或字段非法只告警跳过。
 */
public final class ScheduleStore {
    public static final String DIRECTORY_NAME = ".schedules";
    public static final int MAX_SCHEDULES = 50;
    public static final int MAX_NAME_CHARS = 100;
    public static final int MAX_COMMAND_CHARS = 2000;
    public static final int MAX_OUTPUT_CHARS = 2000;
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern ID = Pattern.compile("schedule_[0-9a-f]{8}");
    private static final Set<String> FIELDS = Set.of("id", "name", "cron", "command", "enabled", "createdAt",
            "nextRunAt", "lastRunAt", "lastFinishedAt", "lastStatus", "lastOutput", "runCount");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path directory;

    public ScheduleStore(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /** 新建启用的计划：cron 立即校验，nextRunAt 从 now 之后算起；ID 随机生成，冲突时重试。 */
    public ScheduleRecord create(String name, String cronText, String command, Instant now, ZoneId zone)
            throws IOException {
        String cleanName = validateName(name);
        String cleanCommand = validateCommand(command);
        CronExpression cron = CronExpression.parse(cronText);
        Files.createDirectories(directory);
        if (scheduleFiles().size() >= MAX_SCHEDULES) {
            throw new IllegalArgumentException("定时计划已达到 " + MAX_SCHEDULES + " 个上限，请删除不再需要的计划");
        }
        Instant next = cron.next(now, zone);
        byte[] random = new byte[4];
        for (int attempt = 0; attempt < 100; attempt++) {
            RANDOM.nextBytes(random);
            ScheduleRecord schedule = new ScheduleRecord("schedule_" + HexFormat.of().formatHex(random),
                    cleanName, cron.expression(), cleanCommand, true, now, next, null, null, null, null, 0);
            try {
                Files.writeString(fileOf(schedule.id()), toJson(schedule), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return schedule;
            } catch (FileAlreadyExistsException e) {
                // 极少发生：换一个 ID 再试。
            }
        }
        throw new IOException("无法分配不重复的计划 ID");
    }

    public ScheduleRecord load(String id) throws IOException {
        validateId(id);
        Path file = fileOf(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有 ID 为 " + id + " 的定时计划");
        }
        return parse(file, id);
    }

    /** 按 ID 排序列出全部计划；单个文件无效只告警跳过，目录不存在视为没有计划。 */
    public List<ScheduleRecord> list(Consumer<String> warnings) throws IOException {
        List<ScheduleRecord> schedules = new ArrayList<>();
        for (Path file : scheduleFiles()) {
            String fileName = file.getFileName().toString();
            try {
                schedules.add(parse(file, fileName.substring(0, fileName.length() - ".json".length())));
            } catch (IllegalArgumentException | IOException e) {
                warnings.accept("计划> 跳过 " + fileName + "：" + message(e));
            }
        }
        return schedules;
    }

    /** 调度线程与工具修改计划时整体覆盖；先写临时文件再原子替换。 */
    void save(ScheduleRecord schedule) throws IOException {
        Objects.requireNonNull(schedule);
        validateId(schedule.id());
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, schedule.id() + "-", ".tmp");
        try {
            Files.writeString(temp, toJson(schedule), StandardCharsets.UTF_8);
            AtomicFiles.replace(temp, fileOf(schedule.id()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void delete(String id) throws IOException {
        validateId(id);
        if (!Files.deleteIfExists(fileOf(id))) {
            throw new IllegalArgumentException("没有 ID 为 " + id + " 的定时计划");
        }
    }

    public static String toJson(ScheduleRecord schedule) {
        JSONObject json = new JSONObject(new LinkedHashMap<>());
        json.put("id", schedule.id());
        json.put("name", schedule.name());
        json.put("cron", schedule.cron());
        json.put("command", schedule.command());
        json.put("enabled", schedule.enabled());
        json.put("createdAt", schedule.createdAt().toString());
        json.put("nextRunAt", Objects.toString(schedule.nextRunAt(), null));
        json.put("lastRunAt", Objects.toString(schedule.lastRunAt(), null));
        json.put("lastFinishedAt", Objects.toString(schedule.lastFinishedAt(), null));
        json.put("lastStatus", schedule.lastStatus() == null ? null : schedule.lastStatus().wireName());
        json.put("lastOutput", schedule.lastOutput());
        json.put("runCount", schedule.runCount());
        return JSON.toJSONString(json, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls) + "\n";
    }

    /** 一行一个计划：启停、ID、名称、cron、下一次时间、最近状态与触发次数。 */
    public static String render(List<ScheduleRecord> schedules, ZoneId zone) {
        if (schedules.isEmpty()) {
            return "没有定时计划。用 schedule_create 创建。";
        }
        StringBuilder text = new StringBuilder();
        for (ScheduleRecord schedule : schedules) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(schedule.enabled() ? "[on] " : "[off] ").append(schedule.id()).append("：")
                    .append(schedule.name()).append(" | cron=").append(schedule.cron());
            if (schedule.enabled()) {
                text.append(" | 下次=").append(time(schedule.nextRunAt(), zone));
            } else {
                text.append(" | 已暂停");
            }
            if (schedule.lastStatus() != null) {
                text.append(" | 上次=").append(schedule.lastStatus().marker())
                        .append(schedule.lastStatus().wireName()).append('@')
                        .append(time(schedule.lastRunAt(), zone));
            }
            text.append(" | 触发=").append(schedule.runCount()).append(" 次");
        }
        return text.toString();
    }

    static String time(Instant instant, ZoneId zone) {
        return instant == null ? "无" : DISPLAY.format(ZonedDateTime.ofInstant(instant, zone));
    }

    public static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("计划 ID 必须形如 schedule_ 加 8 位小写十六进制，例如 schedule_0a1b2c3d");
        }
    }

    private List<Path> scheduleFiles() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("定时计划路径不是目录：" + directory);
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "schedule_*.json")) {
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

    private ScheduleRecord parse(Path file, String expectedId) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("计划 " + expectedId + " 不是普通文件");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("计划 " + expectedId + " 文件已被删除");
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
        if (!(json.get("name") instanceof String name) || !(json.get("cron") instanceof String cronText)
                || !(json.get("command") instanceof String command) || !(json.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("name、cron、command、enabled 字段类型错误");
        }
        Instant createdAt = instant(json.get("createdAt"), "createdAt");
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
        Instant nextRunAt = instant(json.get("nextRunAt"), "nextRunAt");
        Instant lastRunAt = instant(json.get("lastRunAt"), "lastRunAt");
        Instant lastFinishedAt = instant(json.get("lastFinishedAt"), "lastFinishedAt");
        ScheduleRunStatus lastStatus = json.get("lastStatus") == null ? null
                : ScheduleRunStatus.parse(requireString(json.get("lastStatus"), "lastStatus"));
        String lastOutput = json.get("lastOutput") == null ? null
                : requireString(json.get("lastOutput"), "lastOutput");
        int runCount = count(json.get("runCount"));
        CronExpression.parse(cronText);
        String cleanName = validateName(name);
        String cleanCommand = validateCommand(command);
        if (lastOutput != null && lastOutput.length() > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException("lastOutput 最多 " + MAX_OUTPUT_CHARS + " 字符");
        }
        if (enabled && nextRunAt == null) {
            throw new IllegalArgumentException("启用的计划必须有 nextRunAt");
        }
        if (!enabled && nextRunAt != null) {
            throw new IllegalArgumentException("暂停的计划不能有 nextRunAt");
        }
        if (lastStatus == null && (runCount != 0 || lastRunAt != null || lastFinishedAt != null || lastOutput != null)) {
            throw new IllegalArgumentException("没有运行记录时 runCount 必须为 0，且运行时间与输出必须为空");
        }
        if (lastStatus == ScheduleRunStatus.RUNNING && (lastRunAt == null || lastFinishedAt != null)) {
            throw new IllegalArgumentException("running 状态必须有 lastRunAt 且没有 lastFinishedAt");
        }
        if (lastStatus != null && lastStatus != ScheduleRunStatus.RUNNING && lastFinishedAt == null) {
            throw new IllegalArgumentException("结束状态必须有 lastFinishedAt");
        }
        return new ScheduleRecord(expectedId, cleanName, cronText, cleanCommand, enabled,
                createdAt, nextRunAt, lastRunAt, lastFinishedAt, lastStatus, lastOutput, runCount);
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

    private static int count(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("runCount 必须是非负整数");
        }
        double asDouble = number.doubleValue();
        if (asDouble != Math.rint(asDouble) || asDouble < 0 || asDouble > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("runCount 必须是非负整数");
        }
        return number.intValue();
    }

    private static String validateName(String name) {
        if (StrUtil.isBlank(name) || name.strip().length() > MAX_NAME_CHARS
                || name.strip().contains("\n") || name.strip().contains("\r")) {
            throw new IllegalArgumentException("name 必须是非空单行文字，最多 " + MAX_NAME_CHARS + " 字符");
        }
        return name.strip();
    }

    private static String validateCommand(String command) {
        if (StrUtil.isBlank(command) || command.strip().length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("command 必须是非空命令，最多 " + MAX_COMMAND_CHARS + " 字符");
        }
        return command.strip();
    }

    private static String message(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage()
                : "读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
