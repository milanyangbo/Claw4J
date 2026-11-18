package io.github.claw4j.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TagProbeTest {

    @Test
    @DisplayName("这条该跑")
    void plainRuns() {
    }

    @Test
    @Tag("docker")
    @DisplayName("这条该被 excludedGroups 排掉")
    void taggedDoesNotRun() {
        throw new IllegalStateException("如果看到这句，说明方法级 @Tag 没被排除");
    }
}
