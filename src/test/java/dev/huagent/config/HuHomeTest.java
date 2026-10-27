package dev.huagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HuHomeTest {
    @TempDir
    Path directory;

    @Test
    void launcherPropertyWinsOverEnvironment() {
        Path property = directory.resolve("from-launcher");
        assertEquals(property, HuHome.resolve(property.toString(), directory.resolve("env").toString(), "ignored"));
    }

    @Test
    void environmentIsUsedWithoutLauncherProperty() {
        Path environment = directory.resolve("env");
        assertEquals(environment, HuHome.resolve(null, " " + environment + " ", "ignored"));
        assertEquals(environment, HuHome.resolve("  ", environment.toString(), "ignored"));
    }

    @Test
    void defaultsToDotDirectoryInUserHome() {
        assertEquals(directory.resolve(".hu-agent"), HuHome.resolve(null, null, directory.toString()));
    }
}
