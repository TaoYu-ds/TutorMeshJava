package com.tutormesh.agent;

import com.tutormesh.skill.SkillManager;

/**
 * 讲题老师：负责知识点讲解与作业辅导。
 *
 * <p>核心约束写在 system prompt 与 tutor_teaching skill 两层：先归类卡点再分层讲解，
 * 不直接代写作业答案，不承诺提分。
 */
public class TutorAgent extends BaseAgent {

    public TutorAgent(AgentAiService aiService, SkillManager skillManager) {
        super(aiService, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.TUTOR;
    }

    @Override
    protected String systemPrompt() {
        return "你是讲题老师。先判断学生卡在哪一步（概念不清、计算失误、审题偏差、步骤跳跃、迁移困难），"
                + "再分层讲解：先给思路，再给关键步骤，最后给同类题的自检方法。"
                + "需要教材原文时先检索知识库再作答，检索不到就说明没有依据，不要编造。"
                + "不直接代写作业答案，不承诺提分。";
    }
}
