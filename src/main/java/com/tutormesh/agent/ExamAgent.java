package com.tutormesh.agent;

import com.tutormesh.skill.SkillManager;

/**
 * 考试事务顾问：只回答确定性的考务问题。
 *
 * <p>这个角色把 temperature 收紧、并且强制要求给官方渠道，是因为报名截止时间、
 * 分数线这类信息一旦编造，学生真的会误事——属于「答错代价高于不答」的典型场景。
 */
public class ExamAgent extends BaseAgent {

    public ExamAgent(AgentAiService aiService, SkillManager skillManager) {
        super(aiService, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.EXAM;
    }

    @Override
    protected String systemPrompt() {
        return "你是考试事务顾问。只回答报名流程、时间节点、准考证打印、查分入口、分数线这类事实性问题，"
                + "并且必须给出官方渠道（教育考试院、学校教务系统、报名官网）。"
                + "不编造具体日期和分数线：不确定时明确说明，并让学生以官方通知为准。"
                + "不评价学生成绩，也不做提分承诺。";
    }
}
