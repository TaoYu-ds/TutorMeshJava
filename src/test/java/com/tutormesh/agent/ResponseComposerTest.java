package com.tutormesh.agent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResponseComposerTest {
    AgentResponse response(AgentType type,String content,boolean ok) {return new AgentResponse(type,content,ok,1,1,false,"",true,false,false,"");}
    AgentRequest request() {return AgentRequest.of("这道题不会做，顺便帮我排个复习计划","u","c","",List.of());}
    @Test void failurePreservesPrimaryAndSecondaryAnswersWithoutInternalTags() {
        var composer=new ResponseComposer((s,u,t,m)->{throw new IllegalStateException("fixture_unavailable");},null,AgentProfiles.defaults());
        String result=composer.compose(request(),List.of(response(AgentType.TUTOR,"讲题建议",true),response(AgentType.PLAN,"计划建议",true)),AgentType.PLAN);
        assertTrue(result.indexOf("计划建议")<result.indexOf("讲题建议"));assertTrue(result.contains("补充说明"));
        assertFalse(result.contains("Agent"));assertFalse(result.contains("[plan"));assertFalse(result.contains("主处理"));
    }
    @Test void singleOrFailedResponsesDoNotTriggerAnUnnecessaryModelCall() {
        AtomicInteger calls=new AtomicInteger();var composer=new ResponseComposer((s,u,t,m)->{calls.incrementAndGet();return "unused";},null,AgentProfiles.defaults());
        assertEquals("有效回复",composer.compose(request(),List.of(response(AgentType.GENERAL,"有效回复",true)),AgentType.GENERAL));
        composer.compose(request(),List.of(response(AgentType.GENERAL,"失败回复",false)),AgentType.GENERAL);assertEquals(0,calls.get());
    }
    @Test void composerReceivesConflictRulesAndItsConfiguredBudget() {
        var settings=new AgentProfiles(new MockEnvironment().withProperty("TUTORMESH_COMPOSER_MAX_TOKENS","650"));
        var composer=new ResponseComposer((s,u,t,m)->{
            assertTrue(s.contains("若结论冲突"));assertTrue(s.contains("不得新增"));assertTrue(u.contains("主处理角色：plan"));assertEquals(650,m);return "整合后的结果";
        },null,settings);
        assertEquals("整合后的结果",composer.compose(request(),List.of(response(AgentType.TUTOR,"讲题意见",true),response(AgentType.PLAN,"计划意见",true)),AgentType.PLAN));
    }
}
