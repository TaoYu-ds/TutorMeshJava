package com.tutormesh.agent;

import com.tutormesh.intent.*;
import com.tutormesh.tool.*;
import com.tutormesh.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentProfileTest {
    @Test void defaultRoleContractsMatchPythonAndConfigurationIsValidated() {
        var profiles=AgentProfiles.defaults();
        assertEquals(.3,profiles.forType(AgentType.GENERAL).temperature());assertEquals(900,profiles.forType(AgentType.GENERAL).maxOutputTokens());
        assertEquals(.1,profiles.forType(AgentType.TUTOR).temperature());assertEquals(1200,profiles.forType(AgentType.TUTOR).maxOutputTokens());
        assertEquals(.2,profiles.forType(AgentType.PLAN).temperature());assertEquals(1100,profiles.forType(AgentType.PLAN).maxOutputTokens());
        assertEquals(0,profiles.forType(AgentType.EXAM).temperature());assertEquals(800,profiles.forType(AgentType.EXAM).maxOutputTokens());
        assertTrue(profiles.forType(AgentType.PLAN).contract().contains("校验每天负荷"));
        assertTrue(profiles.forType(AgentType.EXAM).contract().contains("不编造"));
        assertThrows(IllegalArgumentException.class,()->new AgentProfiles(new MockEnvironment().withProperty("TUTORMESH_TUTOR_TEMPERATURE","NaN")));
        assertThrows(IllegalArgumentException.class,()->new AgentProfiles(new MockEnvironment().withProperty("TUTORMESH_COMPOSER_TIMEOUT_S","0")));
    }
    @Test void roleParametersAndStructuredInputReachNativeAiServices() {
        var captured=new AtomicReference<ChatRequest>();
        ChatModel model=new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) {
            captured.set(request);return ChatResponse.builder().aiMessage(AiMessage.from("已说明核验边界")).finishReason(FinishReason.STOP).build();
        }};
        var config=new AgentProfiles(new MockEnvironment().withProperty("TUTORMESH_PLAN_MODEL","plan-fixture")
                .withProperty("tutormesh.agents.plan.temperature","0.05").withProperty("TUTORMESH_PLAN_MAX_TOKENS","777"));
        var mapper=new ObjectMapper();var registry=new ToolRegistry(mapper);new RoleToolRegistration(registry).register();
        var ai=new AgentAiService(model,registry,mapper,PromptBudget.defaults(),LlmMetrics.isolated(),config);
        var request=AgentRequest.of("帮我排一下这周的复习计划","u","c","背景",List.of(),new IntentResult(IntentCategory.STUDY_PLAN,.95,UrgencyLevel.LOW,"request",Map.of("exam",List.of("期末")),"",0,Map.of(),"local"));
        assertTrue(new PlanAgent(ai,null).handle(request).success());
        var call=captured.get();assertEquals("plan-fixture",call.modelName());
        assertEquals(.05,call.parameters().temperature());assertEquals(777,call.parameters().maxOutputTokens());
        assertTrue(call.messages().getFirst().toString().contains("处理流程"));
        assertTrue(call.messages().getLast().toString().contains("角色输入包"));assertTrue(call.messages().getLast().toString().contains("期末"));
        assertEquals(Set.of("check_study_load"),new HashSet<>(call.toolSpecifications().stream().map(t->t.name()).toList()));
        assertEquals(config.fingerprint(),ai.withTools(registry.fork()).profiles().fingerprint());
    }
}
