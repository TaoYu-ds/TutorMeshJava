package com.tutormesh.memory;

import java.util.*;

public final class ConversationState {
    private ConversationState() {}
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> previous, Map<String, List<String>> entities, String group, String message, String requestId) {
        // 会话域按意图组划分：查询与请求归到 tutoring，其余沿用意图组名
        String domain = Set.of("request", "query").contains(group) ? "tutoring" : group;
        boolean neutral = Set.of("greeting", "feedback", "other", "complaint", "request", "escalation").contains(domain);
        Map<String, List<String>> old = previous.get("entities") instanceof Map<?, ?> value ? (Map<String, List<String>>) value : Map.of();
        // 换了章节视为新任务，需要重置继承的槽位
        boolean changedChapter = !entities.getOrDefault("chapter", List.of()).isEmpty() && !old.getOrDefault("chapter", List.of()).isEmpty() && !entities.get("chapter").equals(old.get("chapter"));
        boolean newTask = message.matches("(?s).*(?:(?:换个|新的|另一个)(?:问题|知识点)|另一道题|下一题).*");
        if (neutral) domain = !newTask ? String.valueOf(previous.getOrDefault("domain", entities.getOrDefault("chapter", List.of()).isEmpty() ? "general" : "tutoring")) : (entities.getOrDefault("chapter", List.of()).isEmpty() ? "general" : "tutoring");
        boolean keep = !newTask && !changedChapter && (neutral || domain.equals(previous.get("domain")));
        Map<String, List<String>> merged = new LinkedHashMap<>(keep ? old : Map.of());
        Map<String, Object> sources = new LinkedHashMap<>();
        if (keep && previous.get("sources") instanceof Map<?, ?> prior) prior.forEach((k,v) -> sources.put(String.valueOf(k), v));
        entities.forEach((key, value) -> {
            if (!value.isEmpty()) { merged.put(key, List.copyOf(value)); sources.put(key, requestId); }
            else merged.putIfAbsent(key, List.of());
        });
        return Map.of("domain", domain, "entities", merged, "sources", sources);
    }
}
