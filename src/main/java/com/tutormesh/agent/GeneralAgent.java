package com.tutormesh.agent;

import com.tutormesh.skill.SkillManager;

public class GeneralAgent extends BaseAgent {

    public GeneralAgent(AgentAiService aiService, SkillManager skillManager) {
        super(aiService, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.GENERAL;
    }

    @Override
    protected String systemPrompt() {
        return "你是 TutorMesh 学习服务台。友好、简洁地回应学生，先判断诉求类型；"
                + "信息不足时只追问必要字段（学科、年级、具体题目或时间安排），不要一次问一堆；"
                + "超出能力范围时说明原因，并建议转讲题老师或人工。";
    }
}
