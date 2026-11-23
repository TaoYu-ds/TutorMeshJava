package com.tutormesh.llm;

import com.tutormesh.config.TutorMeshProperties;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class LangChain4jLlmGateway implements LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmGateway.class);
    private final ChatModel model;
    private final TutorMeshProperties properties;
    private final LlmMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public LangChain4jLlmGateway(ObjectProvider<ChatModel> models, TutorMeshProperties properties, PromptBudget budget, LlmMetrics metrics) {
        this.model = new ManagedChatModel(models.getIfAvailable(), budget, metrics, "auxiliary");
        this.properties = properties; this.metrics = metrics;
    }
    public LangChain4jLlmGateway(ObjectProvider<ChatModel> models, TutorMeshProperties properties) {
        this(models, properties, new PromptBudget(properties, new com.fasterxml.jackson.databind.ObjectMapper()), LlmMetrics.isolated());
    }

    @Override
    public String chat(String system, String user, double temperature, int tokens) {
        try { return chatRequired(system, user, temperature, tokens); }
        catch (RuntimeException ex) {
            if (!properties.getLlm().isFallbackEnabled()) throw ex;
            metrics.fallback("text", LlmMetrics.category(ex));
            log.warn("LangChain4j chat failed, using local fallback: {}", ex.getClass().getSimpleName());
            return fallback(user);
        }
    }

    @Override
    public String chatRequired(String system, String user, double temperature, int tokens) {
        return judgeChat(system, user, temperature, tokens, "");
    }

    @Override
    public String judgeChat(String system, String user, double temperature, int tokens, String modelName) {
        List<ChatMessage> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) messages.add(SystemMessage.from(system));
        messages.add(UserMessage.from(user == null || user.isBlank() ? " " : user));
        var request = ChatRequest.builder().messages(messages).temperature(temperature).maxOutputTokens(tokens);
        if (modelName != null && !modelName.isBlank()) request.modelName(modelName);
        AiMessage answer = invoke(request.build());
        if (answer.text() == null || answer.text().isBlank()) throw new IllegalStateException("empty_model_response");
        return answer.text();
    }

    private AiMessage invoke(ChatRequest request) {
        if (model == null) throw new IllegalStateException("model_unavailable");
        var response = model.chat(request);
        if (response == null || response.aiMessage() == null) throw new IllegalStateException("empty_model_response");
        return response.aiMessage();
    }

    private String fallback(String user) {
        String prompt = user == null ? "" : user.toLowerCase(Locale.ROOT);
        if (prompt.contains("导数") || prompt.contains("怎么算") || prompt.contains("怎么解"))
            return "当前模型服务不可用。这道题建议先写出已知条件与所求，再对照教材例题的步骤逐步核对，稍后重试或联系老师确认。";
        if (prompt.contains("报名") || prompt.contains("考试") || prompt.contains("查分"))
            return "当前模型服务不可用。报名与考试信息请以学校教务或考试院官网公告为准，稍后重试。";
        return "我已收到你的问题。当前模型服务不可用，系统返回了本地降级回复；请稍后重试或转人工处理。";
    }
}
