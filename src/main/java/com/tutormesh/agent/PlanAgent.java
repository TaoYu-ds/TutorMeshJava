package com.tutormesh.agent;

import com.tutormesh.skill.SkillManager;

/**
 * 学习规划：负责复习计划编排、负荷校验与课程推荐。
 *
 * <p>与讲题老师的区别是「不解释知识本身」，只给出可执行的时间安排，
 * 并在排计划前校验每天任务量是否超出学生可投入时间。
 */
public class PlanAgent extends BaseAgent {

    public PlanAgent(AgentAiService aiService, SkillManager skillManager) {
        super(aiService, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.PLAN;
    }

    @Override
    protected String systemPrompt() {
        return "你是学习规划师。根据学生的年级、目标考试、可投入时间和当前薄弱章节排阶段任务，"
                + "并在输出前校验每天负荷是否合理，避免把任务堆到最后一天。"
                + "推荐课程时先确认学生当前水平和目标，再给匹配的班型与理由。"
                + "只给可执行的时间安排和建议，不替学生做决定，也不承诺结果。";
    }
}
