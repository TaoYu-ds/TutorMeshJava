package com.tutormesh.agent;

import com.tutormesh.llm.ManagedChatModel;
import com.tutormesh.llm.PromptBudget;
import com.tutormesh.llm.LlmMetrics;
import com.tutormesh.tool.RegistryToolProvider;
import com.tutormesh.tool.ToolRegistry;
import com.tutormesh.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;

@Component
public class AgentAiService {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final ObjectMapper mapper;
    private final PromptBudget budget;
    private final LlmMetrics metrics;
    private final AgentProfiles profiles;

    @Autowired
    public AgentAiService(ObjectProvider<ChatModel> models, ToolRegistry tools, ObjectMapper mapper, PromptBudget budget, LlmMetrics metrics, AgentProfiles profiles) {
        this(models.getIfAvailable(), tools, mapper, budget, metrics, profiles);
    }

    public AgentAiService(ChatModel model, ToolRegistry tools, ObjectMapper mapper) {
        this(model, tools, mapper, PromptBudget.defaults(), LlmMetrics.isolated());
    }
    public AgentAiService(ChatModel model, ToolRegistry tools, ObjectMapper mapper, PromptBudget budget, LlmMetrics metrics) {
        this(model, tools, mapper, budget, metrics, AgentProfiles.defaults());
    }
    public AgentAiService(ChatModel model, ToolRegistry tools, ObjectMapper mapper, PromptBudget budget, LlmMetrics metrics, AgentProfiles profiles) {
        this.model = model; this.tools = tools; this.mapper = mapper; this.budget = budget; this.metrics = metrics;
        this.profiles = profiles;
    }

    public AgentAiService withTools(ToolRegistry isolatedTools) { return new AgentAiService(model, isolatedTools, mapper, budget, metrics, profiles); }
    public AgentProfiles profiles() { return profiles; }

    public Result<String> reply(AgentType type, String system, AgentRequest request, List<ToolCallTrace> traces) {
        // This memory is staged for one run. ConversationStore alone commits durable user/assistant turns.
        var memory = MessageWindowChatMemory.withMaxMessages(100);
        memory.add(SystemMessage.from(system));
        if (request.history() != null) for (var entry : request.history()) {
            String content = entry.get("content");
            if (content == null || content.isBlank()) continue;
            switch (entry.getOrDefault("role", "")) {
                case "user" -> memory.add(UserMessage.from(content));
                case "assistant" -> memory.add(AiMessage.from(content));
                default -> { /* Persisted history cannot introduce system instructions. */ }
            }
        }
        var provider = new RegistryToolProvider(tools, mapper, type.name().toLowerCase(Locale.ROOT), request.evidence(), traces, budget, request);
        var assistant = AiServices.builder(AgentAssistant.class).chatModel(new ManagedChatModel(model, budget, metrics, "agent")).chatMemory(memory)
                .systemMessageProvider(ignored -> system)
                .toolProvider(provider).maxSequentialToolsInvocations(4)
                .hallucinatedToolNameStrategy(call -> ToolExecutionResultMessage.from(call, provider.execute(call)))
                .build();
        AgentProfile profile = profiles.forType(type);
        var parameters = ChatRequestParameters.builder().temperature(profile.temperature()).maxOutputTokens(profile.maxOutputTokens());
        if (!profile.model().isBlank()) parameters.modelName(profile.model());
        Result<String> result = assistant.reply(budget.agentPrompt(request) + "\n[角色输入包]\n" + rolePacket(type, request), parameters.build());
        if (result.content() == null || result.content().isBlank()) throw new IllegalStateException("empty_response");
        return result;
    }
    private String rolePacket(AgentType type, AgentRequest request) {
        var packet = new java.util.LinkedHashMap<String, Object>();
        packet.put("agent_type", type.name().toLowerCase(Locale.ROOT)); packet.put("intent", request.intent());
        packet.put("intent_group", request.intentGroup()); packet.put("urgency", request.urgency());
        packet.put("intent_confidence", request.intentConfidence()); packet.put("available_entities", request.entities());
        if (type == AgentType.GENERAL) packet.put("triage_targets", List.of("tutor", "plan", "exam", "escalation"));
        if (type == AgentType.TUTOR) packet.put("risk_boundary", "不得索要账号密码或验证码；不得给出可能伤害学生的建议；工具不访问外部教务系统");
        if (type == AgentType.PLAN) packet.put("risk_boundary", "学生提供的年级与目标仅为参考信息，不得承诺提分、保过或一定排得下计划");
        try { return budget.truncate(mapper.writeValueAsString(packet), 2000); }
        catch (Exception ex) { throw new IllegalStateException("invalid_role_input", ex); }
    }
}
