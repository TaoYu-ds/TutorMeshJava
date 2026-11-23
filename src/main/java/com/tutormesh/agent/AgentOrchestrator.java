package com.tutormesh.agent;

import com.tutormesh.intent.IntentCategory;
import com.tutormesh.intent.IntentRecognizer;
import com.tutormesh.intent.IntentResult;
import com.tutormesh.intent.UrgencyLevel;
import com.tutormesh.trace.RequestTraceStore;
import com.tutormesh.trace.RequestToolTrace;
import com.tutormesh.trace.ToolCallTrace;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentOrchestrator {

    private final IntentRecognizer intentRecognizer;
    private final ResponseComposer composer;
    private final Map<AgentType, List<BaseAgent>> pool;
    private final RequestTraceStore traceStore;
    private final Map<IntentCategory, AgentType> routing = new EnumMap<>(IntentCategory.class);

    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore) {
        this(intentRecognizer, pool, traceStore, (com.tutormesh.llm.LlmGateway) null);
    }
    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore, com.tutormesh.llm.LlmGateway llm) {
        this(intentRecognizer, pool, traceStore, new ResponseComposer(llm, null, AgentProfiles.defaults()));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore,
                             ResponseComposer composer) {
        this.composer = composer;
        this.intentRecognizer = intentRecognizer;
        this.pool = pool;
        this.traceStore = traceStore;
        // 意图 → 教学角色的静态映射；未列出的意图一律走 GENERAL 分诊
        routing.put(IntentCategory.KNOWLEDGE_QUERY, AgentType.TUTOR);
        routing.put(IntentCategory.HOMEWORK_HELP, AgentType.TUTOR);
        routing.put(IntentCategory.STUDY_PLAN, AgentType.PLAN);
        routing.put(IntentCategory.COURSE_RECOMMEND, AgentType.PLAN);
        routing.put(IntentCategory.EXAM_INFO, AgentType.EXAM);
        routing.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
        routing.put(IntentCategory.HUMAN_HANDOFF, AgentType.ESCALATION);
    }

    public OrchestratorResult run(AgentRequest request) {
        return run(request, List.of());
    }

    public OrchestratorResult run(AgentRequest request, List<ToolCallTrace> externalToolCalls) {
        Instant start = Instant.now();
        AgentRequest req = request;
        if (req.intent() == null) {
            IntentResult intentResult = intentRecognizer.recognize(req.message(), req.history());
            req = req.withIntent(intentResult);
        }

        if (needsClarification(req)) {
            OrchestratorResult result = new OrchestratorResult(
                    req.requestId(),
                    "我还不能确定你想解决哪类问题。请补充一下：是想弄懂某个知识点、某道题不会做、需要排复习计划，还是咨询考试与课程信息？",
                    AgentType.GENERAL,
                    req.intent(),
                    false,
                    Duration.between(start, Instant.now()).toMillis(),
                    List.of(AgentType.GENERAL),
                    AgentType.GENERAL,
                    List.of(),
                    collectToolNames(externalToolCalls),
                    collectToolCalls(externalToolCalls),
                    "低置信度 OTHER 意图，先澄清用户需求",
                    req.intentConfidence()
            );
            recordTrace(req, result);
            return result;
        }

        RoutingDecision decision = routeDecision(req);
        if (decision.multiAgent()) {
            return runParallel(req, decision, externalToolCalls);
        }

        AgentResponse response = execute(req, decision.primaryAgent());
        boolean escalated = response.escalate()
                || req.urgency() == UrgencyLevel.CRITICAL
                || req.intent() == IntentCategory.ESCALATION
                || req.intent() == IntentCategory.HUMAN_HANDOFF;
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                response.content(),
                response.agentType(),
                req.intent(),
                escalated,
                Duration.between(start, Instant.now()).toMillis(),
                List.of(response.agentType()),
                decision.primaryAgent(),
                List.of(),
                collectToolNames(externalToolCalls, response),
                collectToolCalls(externalToolCalls, response),
                decision.reason(),
                decision.confidence(),
                response.success() ? (response.toolCalls().stream().anyMatch(t -> !t.success()) ? "partial" : "success") : "failed",
                !response.success(), response.success() ? null : "agent_unavailable"
        );
        recordTrace(req, result);
        return result;
    }

    public Optional<RequestToolTrace> getToolTrace(String requestId) {
        return traceStore.find(requestId);
    }

    public List<RequestToolTrace> getRecentToolTraces(int limit) {
        return traceStore.recent(limit);
    }

    private OrchestratorResult runParallel(AgentRequest req, RoutingDecision decision, List<ToolCallTrace> externalToolCalls) {
        Instant start = Instant.now();
        List<AgentType> targets = decision.agentTypes();
        List<AgentResponse> responses;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<AgentResponse>> futures = targets.stream().map(type -> executor.submit(() -> execute(req, type))).toList();
            responses = new ArrayList<>();
            try { for (var future : futures) responses.add(future.get()); }
            catch (InterruptedException ex) {
                futures.forEach(f -> f.cancel(true)); Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", ex);
            } catch (java.util.concurrent.ExecutionException ex) { throw new IllegalStateException(ex.getCause()); }
        }
        String content = composer.compose(req, responses, decision.primaryAgent());
        boolean allFailed = responses.stream().noneMatch(AgentResponse::success);
        boolean escalate = responses.stream().anyMatch(AgentResponse::escalate);
        List<AgentType> agentTypes = responses.stream()
                .filter(AgentResponse::success)
                .map(AgentResponse::agentType)
                .toList();
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                content,
                decision.primaryAgent(),
                req.intent(),
                escalate,
                Duration.between(start, Instant.now()).toMillis(),
                agentTypes.isEmpty() ? targets : agentTypes,
                decision.primaryAgent(),
                decision.supportingAgents(),
                collectToolNames(externalToolCalls, responses),
                collectToolCalls(externalToolCalls, responses),
                decision.reason(),
                decision.confidence(),
                allFailed ? "failed" : (responses.stream().allMatch(AgentResponse::success) && responses.stream().flatMap(r -> r.toolCalls().stream()).allMatch(ToolCallTrace::success) ? "success" : "partial"),
                allFailed, allFailed ? "agents_unavailable" : null
        );
        recordTrace(req, result);
        return result;
    }

    private AgentType route(IntentCategory intent, UrgencyLevel urgency) {
        if (urgency == UrgencyLevel.CRITICAL) {
            return AgentType.ESCALATION;
        }
        AgentType target = routing.get(intent);
        if (target != null && pool.containsKey(target)) {
            return target;
        }
        return AgentType.GENERAL;
    }

    private RoutingDecision routeDecision(AgentRequest req) {
        if (req.urgency() == UrgencyLevel.CRITICAL) {
            return new RoutingDecision(AgentType.ESCALATION, List.of(), "紧急度为 CRITICAL，触发升级路由", 1.0);
        }
        if (req.intent() == IntentCategory.ESCALATION || req.intent() == IntentCategory.HUMAN_HANDOFF) {
            String intent = req.intent() == null ? "unknown" : req.intent().name().toLowerCase(Locale.ROOT);
            return new RoutingDecision(
                    AgentType.ESCALATION,
                    List.of(),
                    "意图为 " + intent + "，触发升级路由",
                    Math.max(req.intentConfidence(), 0.8)
            );
        }

        Map<AgentType, Double> scores = domainScores(req);
        Map<AgentType, Double> availableScores = new EnumMap<>(AgentType.class);
        scores.forEach((agentType, score) -> {
            if (agentType == AgentType.GENERAL || (pool.containsKey(agentType) && pool.get(agentType).stream().anyMatch(a -> a.stats().available()))) {
                availableScores.put(agentType, score);
            }
        });
        if (availableScores.isEmpty()) {
            return new RoutingDecision(AgentType.GENERAL, List.of(), "无可用专属 Agent，降级到 GeneralAgent", 0.1);
        }

        List<Map.Entry<AgentType, Double>> ordered = availableScores.entrySet().stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .toList();
        AgentType primary = ordered.getFirst().getKey();
        double primaryScore = ordered.getFirst().getValue();
        List<AgentType> supportingAgents = collaborationTargets(req).stream().filter(t -> t != primary && availableScores.containsKey(t)).toList();
        if (supportingAgents.isEmpty()) supportingAgents = ordered.stream()
                .skip(1)
                .filter(entry -> entry.getKey() != AgentType.GENERAL)
                .filter(entry -> entry.getValue() >= 0.45 && entry.getValue() >= primaryScore * 0.55)
                .map(Map.Entry::getKey)
                .toList();
        return new RoutingDecision(
                primary,
                supportingAgents,
                routingReason(req, availableScores, primary, supportingAgents),
                round(Math.min(primaryScore, 1.0))
        );
    }

    private Map<AgentType, Double> domainScores(AgentRequest req) {
        String msg = req.message() == null ? "" : req.message().toLowerCase(Locale.ROOT);
        Map<AgentType, Double> scores = new EnumMap<>(AgentType.class);
        scores.put(AgentType.GENERAL, 0.1);
        scores.put(AgentType.TUTOR, 0.0);
        scores.put(AgentType.PLAN, 0.0);
        scores.put(AgentType.EXAM, 0.0);

        // 分诊类意图：通用学习服务台兜住
        if (List.of(
                IntentCategory.QUERY,
                IntentCategory.COMPLAINT,
                IntentCategory.GREETING,
                IntentCategory.FEEDBACK,
                IntentCategory.OTHER
        ).contains(req.intent())) {
            scores.merge(AgentType.GENERAL, 0.55, Double::sum);
        }

        // 讲题类意图
        if (List.of(
                IntentCategory.KNOWLEDGE_QUERY,
                IntentCategory.HOMEWORK_HELP
        ).contains(req.intent())) {
            scores.merge(AgentType.TUTOR, 0.75, Double::sum);
        }

        // 规划类意图：复习计划与选课建议都归 PLAN
        if (List.of(
                IntentCategory.STUDY_PLAN,
                IntentCategory.COURSE_RECOMMEND
        ).contains(req.intent())) {
            scores.merge(AgentType.PLAN, 0.75, Double::sum);
        }

        // 考务类意图
        if (req.intent() == IntentCategory.EXAM_INFO) {
            scores.merge(AgentType.EXAM, 0.80, Double::sum);
        }

        // 关键词兜底：意图置信度低时用字面证据补分
        long tutorHits = countHits(msg, "怎么做", "不会", "步骤", "错在哪", "为什么", "推导", "公式", "定义", "区别", "看懂");
        long planHits = countHits(msg, "计划", "安排", "复习", "规划", "每天", "几天", "时间", "进度", "推荐", "报班");
        long examHits = countHits(msg, "报名", "考试时间", "查分", "分数线", "准考证", "考场", "什么时候考", "出分");
        long generalHits = countHits(msg, "你好", "谢谢", "投诉", "教材", "课程表", "学习记录", "帮助");

        scores.merge(AgentType.TUTOR, Math.min(0.45, tutorHits * 0.15), Double::sum);
        scores.merge(AgentType.PLAN, Math.min(0.45, planHits * 0.15), Double::sum);
        scores.merge(AgentType.EXAM, Math.min(0.45, examHits * 0.18), Double::sum);
        scores.merge(AgentType.GENERAL, Math.min(0.35, generalHits * 0.12), Double::sum);

        // 实体证据：命中章节说明是具体题目，命中考试名说明是考务问题
        Map<String, List<String>> entities = req.entities() == null ? Map.of() : req.entities();
        if (!entities.getOrDefault("chapter", List.of()).isEmpty()) {
            scores.merge(AgentType.TUTOR, 0.2, Double::sum);
        }
        if (!entities.getOrDefault("exam", List.of()).isEmpty()) {
            scores.merge(AgentType.EXAM, 0.2, Double::sum);
        }
        if (!entities.getOrDefault("grade", List.of()).isEmpty()) {
            scores.merge(AgentType.PLAN, 0.1, Double::sum);
        }

        scores.replaceAll((agentType, score) -> round(score));
        return scores;
    }

    private String routingReason(
            AgentRequest req,
            Map<AgentType, Double> scores,
            AgentType primaryAgent,
            List<AgentType> supportingAgents
    ) {
        String scoreText = scores.entrySet().stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT) + "=" + String.format(Locale.ROOT, "%.2f", entry.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String supportText = supportingAgents.stream()
                .map(agent -> agent.name().toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        String intent = req.intent() == null ? "unknown" : req.intent().name().toLowerCase(Locale.ROOT);
        return "intent=" + intent
                + ", group=" + (req.intentGroup() == null ? "unknown" : req.intentGroup())
                + ", primary=" + primaryAgent.name().toLowerCase(Locale.ROOT)
                + ", supporting=" + supportText
                + ", scores=[" + scoreText + "]";
    }

    private List<AgentType> collaborationTargets(AgentRequest request) {
        String msg = request.message() == null ? "" : request.message().toLowerCase(Locale.ROOT);
        LinkedHashSet<AgentType> targets = new LinkedHashSet<>();
        // 讲题诉求：知识点讲解或作业辅导，可以同时触发讲题与规划协作
        if (List.of(IntentCategory.KNOWLEDGE_QUERY, IntentCategory.HOMEWORK_HELP).contains(request.intent())
                || containsAny(msg, "怎么做", "不会", "步骤", "错在哪", "推导", "公式")) {
            targets.add(AgentType.TUTOR);
        }
        if (List.of(IntentCategory.STUDY_PLAN, IntentCategory.COURSE_RECOMMEND).contains(request.intent())
                || containsAny(msg, "计划", "安排", "复习", "规划", "报班", "推荐")) {
            targets.add(AgentType.PLAN);
        }
        if (request.intent() == IntentCategory.EXAM_INFO
                || containsAny(msg, "报名", "查分", "分数线", "准考证", "考场")) {
            targets.add(AgentType.EXAM);
        }
        return new ArrayList<>(targets.stream().filter(pool::containsKey).toList());
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long countHits(String text, String... keywords) {
        long hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }

    private boolean needsClarification(AgentRequest req) {
        if (req.intent() != IntentCategory.OTHER) {
            return false;
        }
        String text = req.message() == null ? "" : req.message().trim();
        if (text.length() <= 2) {
            return false;
        }
        return req.intentConfidence() < 0.5;
    }

    private AgentResponse execute(AgentRequest req, AgentType agentType) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("interrupted");
        if (agentType == AgentType.ESCALATION) return pool.getOrDefault(AgentType.ESCALATION, List.of()).stream().findFirst()
                .orElseGet(() -> new EscalationAgent(null, null)).handle(req);
        BaseAgent agent = availableAgent(agentType, req.requestId()).orElseGet(() -> availableAgent(AgentType.GENERAL, req.requestId()).orElse(null));
        if (agent == null) {
            return new AgentResponse(AgentType.GENERAL, "服务暂时不可用，请稍后重试。", false, 0.0, 0, false, "", false, false, false, "service unavailable");
        }
        AgentResponse response = agent.handle(req);
        if (!response.success() && agentType != AgentType.GENERAL && !req.operations().writeAttempted()) {
            AgentResponse fallback = availableAgent(AgentType.GENERAL, req.requestId()).map(a -> a.handle(req)).orElse(response);
            List<ToolCallTrace> attempts = new ArrayList<>(response.toolCalls());
            attempts.add(new ToolCallTrace("agent_fallback", false, true, false, false, response.latencyMs(), "specialist_failed"));
            attempts.addAll(fallback.toolCalls());
            return new AgentResponse(fallback.agentType(), fallback.content(), fallback.success(), fallback.confidence(), fallback.latencyMs(), fallback.escalate(), "", fallback.toolSuccess(), false, false, fallback.toolError(), attempts);
        }
        return response;
    }

    private void recordTrace(AgentRequest req, OrchestratorResult result) {
        traceStore.record(new RequestToolTrace(
                result.requestId(),
                Instant.now().toString(),
                "chat",
                req.userId(),
                req.conversationId(),
                result.intent() == null ? null : result.intent().name().toLowerCase(Locale.ROOT),
                req.intentGroup(),
                result.agentType().name().toLowerCase(Locale.ROOT),
                result.primaryAgent() == null ? null : result.primaryAgent().name().toLowerCase(Locale.ROOT),
                result.supportingAgents().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).toList(),
                result.toolsUsed(),
                result.toolCalls(),
                result.toolCalls().stream().anyMatch(t -> t.success() && (t.toolName().equals("search_knowledge_base") || t.toolName().equals("knowledge_search"))),
                result.escalated(),
                result.latencyMs()
        ));
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        List<ToolCallTrace> calls = new ArrayList<>();
        if (externalToolCalls != null) {
            calls.addAll(externalToolCalls);
        }
        if (responses != null) {
            for (AgentResponse response : responses) {
                if (response == null) {
                    continue;
                }
                calls.addAll(response.toolCalls());
                if (response.toolName() != null && !response.toolName().isBlank()) {
                    calls.add(toTrace(response, response.toolName()));
                }
            }
        }
        return calls;
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses == null ? null : responses.toArray(new AgentResponse[0]));
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private ToolCallTrace toTrace(AgentResponse response, String toolName) {
        return new ToolCallTrace(
                toolName == null ? "" : toolName,
                response.success(),
                !response.success(),
                response.toolCached(),
                response.toolReranked(),
                response.latencyMs(),
                response.toolError() == null ? "" : response.toolError()
        );
    }

    private Optional<BaseAgent> availableAgent(AgentType type, String requestId) {
        return pool.getOrDefault(type, List.of()).stream().sorted(Comparator.comparingDouble((BaseAgent a) -> a.stats().routingScore()).reversed())
                .filter(a -> a.stats().admit(requestId)).findFirst();
    }

    private Optional<BaseAgent> bestAgent(AgentType agentType) {
        return pool.getOrDefault(agentType, List.of()).stream()
                .max(Comparator.comparingDouble(a -> a.stats().routingScore()));
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                BaseAgent agent = agents.get(i);
                Map<String, Object> data = new HashMap<>();
                data.put("total", agent.stats().total());
                data.put("window_samples", agent.stats().windowSamples());
                data.put("recovery_state", agent.stats().recoveryState());
                data.put("success_rate", round(agent.stats().successRate()));
                data.put("avg_ms", round(agent.stats().avgLatencyMs()));
                data.put("monitor_penalty", round(agent.stats().monitorPenalty()));
                data.put("routing_score", round(agent.stats().routingScore()));
                result.put(type.name().toLowerCase(Locale.ROOT) + "_" + i, data);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                agents.get(i).stats().setMonitorPenalty(penalties.getOrDefault(type.name().toLowerCase(Locale.ROOT) + "_" + i, 0.0));
            }
        });
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record RoutingDecision(
            AgentType primaryAgent,
            List<AgentType> supportingAgents,
            String reason,
            double confidence
    ) {
        private List<AgentType> agentTypes() {
            List<AgentType> result = new ArrayList<>();
            result.add(primaryAgent);
            result.addAll(supportingAgents);
            return result;
        }

        private boolean multiAgent() {
            return !supportingAgents.isEmpty();
        }
    }
}
