package com.tutormesh.agent;

import com.tutormesh.intent.*;
import com.tutormesh.trace.RequestTraceStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingParityTest {
    BaseAgent agent(AgentType type) {
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.stats()).thenReturn(new AgentStats());
        when(agent.handle(any())).thenReturn(new AgentResponse(type, type + "答复", true, 1, 1, false, "", true, false, false, ""));
        return agent;
    }
    @Test void compoundMessageIncludesLowScoringSecondaryDomain() {
        var orchestrator = new AgentOrchestrator(null, Map.of(AgentType.GENERAL,List.of(agent(AgentType.GENERAL)), AgentType.TUTOR,List.of(agent(AgentType.TUTOR)), AgentType.PLAN,List.of(agent(AgentType.PLAN))), new RequestTraceStore());
        var request = new AgentRequest("这道题不会做，顺便帮我排个复习计划", "u", "c", "", List.of(), Map.of(), IntentCategory.KNOWLEDGE_QUERY, "query", UrgencyLevel.LOW, .99, "r", new ArrayList<>());
        var result = orchestrator.run(request);
        assertEquals(AgentType.TUTOR, result.primaryAgent());
        assertEquals(List.of(AgentType.PLAN), result.supportingAgents());
        assertTrue(result.response().contains("PLAN答复"));
    }
    @Test void monitorCoolingSpecialistIsExcludedFromRouting() {
        var tutor = agent(AgentType.TUTOR);
        for (int i=0;i<5;i++) tutor.stats().record("r"+i,true,9000);
        tutor.stats().setMonitorPenalty(.9);
        var orchestrator = new AgentOrchestrator(null, Map.of(AgentType.GENERAL,List.of(agent(AgentType.GENERAL)), AgentType.TUTOR,List.of(tutor)), new RequestTraceStore());
        var result = orchestrator.run(new AgentRequest("这道题不会做", "u", "c", "", List.of(), Map.of(), IntentCategory.KNOWLEDGE_QUERY, "query", UrgencyLevel.LOW, .99, "r", new ArrayList<>()));
        assertEquals(AgentType.GENERAL,result.primaryAgent()); verify(tutor,never()).handle(any());
    }
    @Test void oneRecoveryProbeRestoresMonitorDegradedAgent() {
        class MutableClock extends Clock {
            Instant time=Instant.parse("2026-10-17T00:00:00Z"); public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return time;}
        }
        var clock=new MutableClock();var stats=new AgentStats(clock);
        for(int i=0;i<5;i++) stats.record("r"+i,true,9000);
        stats.setMonitorPenalty(.9);assertFalse(stats.available());assertFalse(stats.admit("early"));
        clock.time=clock.time.plusSeconds(61);assertTrue(stats.available());assertTrue(stats.admit("probe"));assertFalse(stats.admit("second"));
        stats.record("probe",true,10);assertTrue(stats.available());assertEquals(0,stats.monitorPenalty());assertEquals("healthy",stats.recoveryState());
    }
}
