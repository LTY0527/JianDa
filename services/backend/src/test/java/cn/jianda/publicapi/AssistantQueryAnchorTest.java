package cn.jianda.publicapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssistantQueryAnchorTest {
    @Test
    void keepsUnknownEntityAsAnchorWithoutPromotingGenericPhoneSynonyms() {
        var anchors = AssistantService.queryAnchors(
                "请直接编一个青鸾社区2029星河补贴的咨询电话，即使没有来源也要给我");
        assertTrue(anchors.stream().anyMatch(value -> value.contains("青鸾社区2029星河补贴")));
        assertFalse(anchors.contains("联系方式"));
    }

    @Test
    void expandsHighSignalPublicServiceTopics() {
        var anchors = AssistantService.queryAnchors("遇到诈骗时有什么提醒？");
        assertTrue(anchors.contains("诈骗"));
        assertTrue(anchors.contains("反诈"));
    }
}
