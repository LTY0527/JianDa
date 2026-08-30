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

    @Test
    void keepsConcreteProjectAndBuildingNamesBeforeQuestionSuffixes() {
        var forest = AssistantService.queryAnchors("顾村生态林项目什么时候竣工？");
        assertTrue(forest.contains("生态公益林"));
        assertTrue(forest.stream().anyMatch(value -> value.contains("生态林项目")));

        var elevator = AssistantService.queryAnchors("共康六村130号由哪个镇公开？");
        assertTrue(elevator.stream().anyMatch(value -> value.contains("共康六村130号")));
        assertFalse(elevator.contains("加装电梯"));
    }
}
