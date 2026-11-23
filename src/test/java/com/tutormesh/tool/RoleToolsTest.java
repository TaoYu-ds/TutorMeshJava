package com.tutormesh.tool;

import com.tutormesh.agent.*;
import com.tutormesh.intent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 教学角色工具的边界测试：工具必须只暴露给对应角色、只使用应用侧传入的数据、
 * 并且不能代替学生完成题目或编造考试信息。
 */
class RoleToolsTest {
    ToolRegistry registry() { var tools = new ToolRegistry(new ObjectMapper()); new RoleToolRegistration(tools).register(); return tools; }
    AgentRequest request(String user, Map<String,List<String>> entities) {
        return AgentRequest.of("这道题不会做",user,"conversation-"+user,"背景",List.of(),
                new IntentResult(IntentCategory.HOMEWORK_HELP,.9,UrgencyLevel.LOW,"request",entities,"",0,Map.of(),"local"));
    }

    @Test void exposesOnlyTheRoleToolWhitelist() {
        var tools=registry();
        assertEquals(Set.of("classify_blocker","build_explanation_plan"),
                new HashSet<>(tools.available("tutor").stream().map(ToolDefinition::name).toList()));
        assertEquals(Set.of("check_study_load"),
                new HashSet<>(tools.available("plan").stream().map(ToolDefinition::name).toList()));
        assertEquals(Set.of("exam_official_channels"),
                new HashSet<>(tools.available("exam").stream().map(ToolDefinition::name).toList()));
        // 跨角色调用必须被拒绝：讲题老师不能排计划，通用角色不能查考试渠道
        assertFalse(tools.call("check_study_load",Map.of("daily_minutes",60,"task_count",5,"days",7),"general",request("u",Map.of())).success());
        assertFalse(tools.call("exam_official_channels",Map.of("exam","高考"),"general",request("u",Map.of())).success());
    }

    @Test void concurrentUsersNeverShareRequestFieldsOrCachedResults() throws Exception {
        var tools=registry();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=List.of("alice","bob").stream()
                    .map(user->executor.submit(()->tools.call("inspect_request_context",Map.of(),"general",request(user,Map.of("chapter",List.of(user+"-章节"))))))
                    .toList();
            for(int i=0;i<tasks.size();i++) {
                var result=tasks.get(i).get();assertTrue(result.success());assertFalse(result.cached());
                var fields=(Map<?,?>)((Map<?,?>)result.data()).get("entities");
                assertEquals(List.of(i==0?"alice-章节":"bob-章节"),fields.get("chapter"));
            }
        }
    }

    @Test void studyLoadFlagsOverloadAndGivesConcreteAdvice() {
        var tools=registry();var req=request("u",Map.of());
        var ok=(Map<?,?>)tools.call("check_study_load",Map.of("daily_minutes",60,"task_count",5,"days",7),"plan",req).data();
        assertEquals(true,ok.get("load_ok"));assertEquals(84,ok.get("per_task_minutes"));
        var tight=(Map<?,?>)tools.call("check_study_load",Map.of("daily_minutes",20,"task_count",5,"days",1),"plan",req).data();
        assertEquals(false,tight.get("load_ok"));
        assertTrue(String.valueOf(tight.get("advice")).contains("减少任务数"));
        assertFalse(tools.call("check_study_load",Map.of("daily_minutes","很多","task_count",5,"days",1),"plan",req).success());
    }

    @Test void blockerClassificationMapsVagueComplaintsToActionableTypes() {
        var tools=registry();var req=request("u",Map.of());
        var concept=(Map<?,?>)tools.call("classify_blocker",Map.of("blocker","我看不懂这个定义到底在说什么"),"tutor",req).data();
        assertEquals("概念不清",concept.get("blocker_type"));assertEquals(false,concept.get("answer_given"));
        var calc=(Map<?,?>)tools.call("classify_blocker",Map.of("blocker","我算到一半符号就搞错了"),"tutor",req).data();
        assertEquals("计算失误",calc.get("blocker_type"));
        var plan=(Map<?,?>)tools.call("build_explanation_plan",Map.of("blocker_type","步骤跳跃","subject","数学"),"tutor",req).data();
        assertEquals(5,((List<?>)plan.get("explanation_steps")).size());
        assertEquals(false,plan.get("final_answer_included"));
    }

    @Test void examChannelsNeverInventDates() {
        var tools=registry();var req=request("u",Map.of());
        var data=(Map<?,?>)tools.call("exam_official_channels",Map.of("exam","四级"),"exam",req).data();
        assertEquals(false,data.get("date_guaranteed"));
        assertTrue(String.valueOf(data.get("channels")).contains("cet.neea.edu.cn"));
        assertTrue(String.valueOf(data.get("note")).contains("官方"));
    }

    @Test void requiredFieldsDoNotAskForAnAlreadyKnownChapter() {
        var base=request("u",Map.of("subject",List.of("数学"),"chapter",List.of("导数")));
        var req=base.withIntent(new IntentResult(IntentCategory.KNOWLEDGE_QUERY,1,UrgencyLevel.LOW,"query",base.entities(),"",0,Map.of(),"local"));
        var data=(Map<?,?>)registry().call("suggest_required_fields",Map.of(),"general",req).data();
        assertEquals(List.of(),data.get("required_fields"));
    }

    @Test void handoffIsDeterministicAndNeverCreatesATicketOrCallsAModel() {
        var req=request("u",Map.of("chapter",List.of("导数")));
        var summary=(HandoffSummary)registry().call("create_handoff_summary",Map.of("reason","学生要求转人工"),"escalation",req).data();
        assertFalse(summary.ticketCreated());
        var response=new EscalationAgent(null,null).handle(req);
        assertTrue(response.escalate());
        assertTrue(response.content().contains("未创建或提交工单"));
        assertEquals("create_handoff_summary",response.toolCalls().getFirst().toolName());
    }
}
