package com.tutormesh.memory;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStateTest {
    @Test void structuredScopeKeysCannotCollideAtColonBoundaries() {
        assertNotEquals(ConversationStore.scope("a:b", "c"), ConversationStore.scope("a", "b:c"));
    }
    @Test void followupInheritsEntitiesButNewChapterResetsThem() {
        var initial = ConversationState.merge(Map.of(),
                Map.of("chapter", List.of("导数"), "exam", List.of("期末")), "request", "这道题怎么做", "r1");
        // 同一任务内的追问：章节不变，实体应继承
        var followup = ConversationState.merge(initial, Map.of("chapter", List.of()), "other", "那第二步呢", "r2");
        assertEquals(List.of("导数"), ((Map<?, ?>) followup.get("entities")).get("chapter"));
        // 换章节：视为新任务，旧实体不应带过来
        var changed = ConversationState.merge(initial, Map.of("chapter", List.of("数列")), "query", "下一题", "r3");
        assertFalse(((Map<?, ?>) changed.get("entities")).containsKey("exam"));
        // 换知识点：同样重置
        var switched = ConversationState.merge(initial, Map.of("grade", List.of("高一")), "tutor", "换个知识点", "r4");
        assertFalse(((Map<?, ?>) switched.get("entities")).containsKey("chapter"));
    }
}
