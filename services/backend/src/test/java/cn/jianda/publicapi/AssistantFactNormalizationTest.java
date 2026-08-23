package cn.jianda.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AssistantFactNormalizationTest {
    @Test
    void normalizesChineseAndIsoDatesToTheSameEvidenceValue() {
        assertEquals(
                AssistantService.normalizeFact("标准自2026-09-01实施"),
                AssistantService.normalizeFact("标准自2026年9月1日实施"));
    }
}
