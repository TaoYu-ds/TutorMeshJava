package com.tutormesh.agent;

import com.tutormesh.skill.SkillManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class AgentConfig {

    @Bean
    Map<AgentType, List<BaseAgent>> agentPool(AgentAiService aiService, SkillManager skillManager, com.tutormesh.config.TutorMeshProperties properties) {
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(aiService, skillManager)),
                AgentType.TUTOR, List.of(new TutorAgent(aiService, skillManager)),
                AgentType.PLAN, List.of(new PlanAgent(aiService, skillManager)),
                AgentType.EXAM, List.of(new ExamAgent(aiService, skillManager)),
                AgentType.ESCALATION, List.of(new EscalationAgent(aiService, skillManager))
        );
        pool.values().forEach(agents -> agents.forEach(agent -> agent.stats().configure(properties.getMonitor().getFallbackPenalty(), properties.getMonitor().getCooldownSeconds())));
        return pool;
    }
}
