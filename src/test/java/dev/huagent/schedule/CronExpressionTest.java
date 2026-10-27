package dev.huagent.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class CronExpressionTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void nextIsStrictlyAfterTheGivenMinute() {
        CronExpression everyMinute = CronExpression.parse("* * * * *");
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"),
                everyMinute.next(Instant.parse("2026-01-01T00:00:30Z"), UTC));
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"),
                everyMinute.next(Instant.parse("2026-01-01T00:00:00Z"), UTC));
    }

    @Test
    void supportsStepsRangesAndLists() {
        CronExpression quarter = CronExpression.parse("*/15 * * * *");
        assertEquals(Instant.parse("2026-01-01T10:15:00Z"),
                quarter.next(Instant.parse("2026-01-01T10:07:59Z"), UTC));
        CronExpression workHours = CronExpression.parse("0 9-17/2 * * 1-5");
        assertEquals(Instant.parse("2026-10-23T09:00:00Z"),
                workHours.next(Instant.parse("2026-10-23T00:00:00Z"), UTC));
        CronExpression list = CronExpression.parse("5,35 8 * * *");
        assertEquals(Instant.parse("2026-01-01T08:35:00Z"),
                list.next(Instant.parse("2026-01-01T08:10:00Z"), UTC));
    }

    @Test
    void dayOfMonthAndDayOfWeekUseVixieOrSemantics() {
        CronExpression firstOrMonday = CronExpression.parse("0 0 1 * 1");
        // 2026-07-01 是周三，7-02 之后的下一个周一是 7-06。
        assertEquals(Instant.parse("2026-07-06T00:00:00Z"),
                firstOrMonday.next(Instant.parse("2026-07-02T00:00:00Z"), UTC));
        // 只有日受限时按“与”理解，周不受限。
        CronExpression monthEnd = CronExpression.parse("0 0 31 * *");
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"),
                monthEnd.next(Instant.parse("2026-01-01T00:00:00Z"), UTC));
        CronExpression sundayAsSeven = CronExpression.parse("0 12 * * 7");
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"),
                sundayAsSeven.next(Instant.parse("2026-10-23T13:00:00Z"), UTC));
    }

    @Test
    void handlesLeapYearsAndMonthLengths() {
        CronExpression leapDay = CronExpression.parse("0 0 29 2 *");
        assertEquals(Instant.parse("2028-02-29T00:00:00Z"),
                leapDay.next(Instant.parse("2026-01-01T00:00:00Z"), UTC));
    }

    @Test
    void findsNextDateWhenEarlierAllowedMonthsLackTheDay() {
        CronExpression cron = CronExpression.parse("0 0 31 4,5 *");
        assertEquals(Instant.parse("2026-05-31T00:00:00Z"),
                cron.next(Instant.parse("2026-04-01T00:00:00Z"), UTC));
    }

    @Test
    void usesHutoolCronSyntax() {
        CronExpression lastDay = CronExpression.parse("0 0 L * *");
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"),
                lastDay.next(Instant.parse("2026-01-01T00:00:00Z"), UTC));
        assertTrue(CronExpression.parse("? ? ? ? ?")
                .matches(Instant.parse("2026-01-01T12:34:56Z"), UTC));
    }

    @Test
    void interpretsWallClockFieldsInTheGivenZone() {
        CronExpression nine = CronExpression.parse("0 9 * * *");
        // 上海 9:00 对应 UTC 1:00。
        assertEquals(Instant.parse("2026-01-01T01:00:00Z"),
                nine.next(Instant.parse("2026-01-01T00:00:00Z"), SHANGHAI));
        assertTrue(nine.matches(Instant.parse("2026-01-01T01:00:00Z"), SHANGHAI));
        assertFalse(nine.matches(Instant.parse("2026-01-01T09:00:00Z"), SHANGHAI));
    }

    @Test
    void matchesIgnoresSecondsAndNanos() {
        CronExpression cron = CronExpression.parse("30 8 * * *");
        assertTrue(cron.matches(Instant.parse("2026-01-01T08:30:59.999Z"), UTC));
        assertFalse(cron.matches(Instant.parse("2026-01-01T08:31:00Z"), UTC));
    }

    @Test
    void trimsOuterWhitespaceAndKeepsExpression() {
        CronExpression cron = CronExpression.parse("  */5   * * * *  ");
        assertEquals("*/5   * * * *", cron.expression());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *",
            "* * 0 * *", "* * * 0 *", "* * * 13 *", "* * * * 8", "*/0 * * * *",
            "a * * * *", "1,,2 * * * *", "1/2/3 * * * *", "1-2-3 * * * *",
            "2147483648 * * * *"})
    void rejectsInvalidExpressions(String text) {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(text), text);
    }

    @Test
    void rejectsOverlongText() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CronExpression.parse("*".repeat(121))).getMessage().contains("120"));
    }

    @Test
    void impossibleDateFailsInsteadOfLoopingForever() {
        CronExpression never = CronExpression.parse("0 0 31 2 *");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> never.next(Instant.parse("2026-01-01T00:00:00Z"), UTC));
        assertTrue(error.getMessage().contains("没有下一次"));
    }
}
