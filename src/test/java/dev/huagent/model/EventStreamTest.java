package dev.huagent.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventStreamTest {
    @Test
    void parsesTypesMultiLineDataCommentsAndLineEndings() throws Exception {
        String body = ": keep-alive\r\n\r\n"
                + "event: message_start\r\ndata: {\"a\":1}\r\n\r\n"
                + "data:第一行\ndata: 第二行\n\n"
                + "event: ping\rdata: {}\r\r"
                + "id: 7\nretry: 100\n\n"
                + "data: 最后没有空行";
        List<String> events = read(body, (type, data) -> true);
        assertEquals(List.of("message_start|{\"a\":1}", "message|第一行\n第二行", "ping|{}", "message|最后没有空行"),
                events);
    }

    @Test
    void stopsReadingWhenListenerSeesTheEnd() throws Exception {
        List<String> events = read(data("1") + data("[DONE]") + data("之后的不读"),
                (type, data) -> !"[DONE]".equals(data));
        assertEquals(List.of("message|1", "message|[DONE]"), events);
    }

    private static String data(String data) {
        return StreamServer.data(data);
    }

    private static List<String> read(String body, EventStream.Listener stop) throws Exception {
        List<String> events = new ArrayList<>();
        EventStream.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), Duration.ofSeconds(5),
                (type, data) -> {
                    events.add(type + "|" + data);
                    return stop.event(type, data);
                });
        return events;
    }
}
