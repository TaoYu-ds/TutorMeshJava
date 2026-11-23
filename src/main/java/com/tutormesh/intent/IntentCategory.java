package com.tutormesh.intent;

/**
 * 学习辅导场景的意图分类。
 *
 * <p>前 7 类是跨业务复用的通用会话意图（换到任何领域都适用），中间 5 类是教育场景专属，
 * OTHER 为低置信度兜底。分组的意义在于：通用意图负责接待、投诉、升级等会话管理，
 * 教育意图负责把问题路由到讲题 / 计划 / 考试三类教学 Agent。
 */
public enum IntentCategory {
    // ── 通用会话意图（跨场景复用）──
    QUERY,          // 查询信息（开课时间、学习记录、教材位置）
    COMPLAINT,      // 表达不满（讲太快、没人回复）
    REQUEST,        // 请求操作（安排复习、拆解题目）
    GREETING,       // 问候
    ESCALATION,     // 要求升级（投诉、换老师）
    FEEDBACK,       // 正面反馈
    HUMAN_HANDOFF,  // 明确要求转人工 / 找老师

    // ── 教育场景意图 ──
    KNOWLEDGE_QUERY,   // 知识点提问（定义、原理、公式、区别）
    HOMEWORK_HELP,     // 作业辅导（这题不会、看看错在哪）
    STUDY_PLAN,        // 学习计划（复习安排、时间分配）
    EXAM_INFO,         // 考试信息（报名、时间、查分、分数线）
    COURSE_RECOMMEND,  // 课程推荐（适合什么课、怎么补）

    OTHER           // 低置信度兜底
}
