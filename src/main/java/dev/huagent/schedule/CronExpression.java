package dev.huagent.schedule;

import cn.hutool.cron.pattern.CronPattern;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

/**
 * 五段 cron 表达式：分 时 日 月 周。
 * 字段解析、取值范围校验和单个表达式的匹配交给 Hutool 的 {@link CronPattern}；
 * 本类只补充 S12 需要的约定：恰好五段、日与周同时受限时按“或”匹配、下一次时间严格晚于给定时刻，
 * 以及提前拒绝永远不存在的日期（Hutool 在部分表达式上会无限回溯）。
 * 时间按传入的时区解释，最短分辨率一分钟。
 */
public final class CronExpression {
    private static final int MAX_TEXT_CHARS = 120;
    /** 探测“日 + 月”是否存在真实日期时的检查天数，覆盖闰日。 */
    private static final int DATE_PROBE_DAYS = 366 * 4;

    private final String expression;
    private final List<CronPattern> patterns;

    private CronExpression(String expression, List<CronPattern> patterns) {
        this.expression = expression;
        this.patterns = patterns;
    }

    public static CronExpression parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空");
        }
        String clean = text.strip();
        if (clean.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("cron 最多 " + MAX_TEXT_CHARS + " 字符");
        }
        String[] fields = clean.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron 必须由五段组成：分 时 日 月 周，例如 */5 * * * *");
        }
        try {
            List<CronPattern> patterns = new ArrayList<>(2);
            if (restricted(fields[2]) && restricted(fields[4])) {
                // Hutool 的单个表达式把日与周按“与”处理。拆成两个表达式再取较早的下一次，得到 Vixie cron 的“或”。
                if (possibleDate(fields[2], fields[3])) {
                    patterns.add(new CronPattern(replace(fields, 4, "*")));
                }
                patterns.add(new CronPattern(replace(fields, 2, "*")));
            } else if (!restricted(fields[2]) || possibleDate(fields[2], fields[3])) {
                patterns.add(new CronPattern(clean));
            }
            return new CronExpression(clean, List.copyOf(patterns));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cron 格式错误：" + e.getMessage(), e);
        }
    }

    public String expression() {
        return expression;
    }

    /** 严格晚于 after 的下一个匹配时刻，按 zone 的本地日期与时间计算。 */
    public Instant next(Instant after, ZoneId zone) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(zone, "zone");
        TimeZone timeZone = TimeZone.getTimeZone(zone);
        Instant best = null;
        for (CronPattern pattern : patterns) {
            Calendar calendar = Calendar.getInstance(timeZone);
            calendar.setTimeInMillis(after.toEpochMilli());
            Instant candidate = pattern.nextMatchAfter(calendar).toInstant();
            if (candidate.isAfter(after) && (best == null || candidate.isBefore(best))) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("cron 在可预见的范围内没有下一次执行时间，请检查是否写成了不存在的日期："
                    + expression);
        }
        return best;
    }

    /** 判断某个时刻是否匹配（秒与纳秒不参与比较）。 */
    public boolean matches(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        TimeZone timeZone = TimeZone.getTimeZone(zone);
        for (CronPattern pattern : patterns) {
            if (pattern.match(timeZone, instant.toEpochMilli(), false)) {
                return true;
            }
        }
        return false;
    }

    /** 日与周同时受限时，先确认“日 + 月”至少存在一个真实日期，避免 Hutool 在不存在的日期上无限回溯。 */
    private static boolean possibleDate(String dayOfMonth, String month) {
        CronPattern probe = new CronPattern("* * " + dayOfMonth + " " + month + " *");
        LocalDate date = LocalDate.of(2000, 1, 1);
        for (int checked = 0; checked < DATE_PROBE_DAYS; checked++) {
            if (probe.match(date.atStartOfDay(), false)) {
                return true;
            }
            date = date.plusDays(1);
        }
        return false;
    }

    private static boolean restricted(String field) {
        return !field.startsWith("*") && !field.startsWith("?");
    }

    private static String replace(String[] fields, int index, String value) {
        String[] copy = fields.clone();
        copy[index] = value;
        return String.join(" ", copy);
    }
}
