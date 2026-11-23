package com.tutormesh.agent;

/**
 * 教学 Agent 的职责类型。
 *
 * <p>GENERAL 负责首轮接待与分诊，TUTOR 负责知识点讲解与作业辅导，
 * PLAN 负责学习计划编排与课程推荐，EXAM 只做考试流程与信息查询，
 * ESCALATION 负责人工交接。每个角色的契约见 resources/agents/profiles.json。
 */
public enum AgentType {
    GENERAL,     // 学习服务台：分诊、澄清、基础问答
    TUTOR,       // 讲题老师：知识点讲解、作业辅导、卡点归类
    PLAN,        // 学习规划：复习计划、负荷校验、课程推荐
    EXAM,        // 考试顾问：报名流程、时间节点、查分入口
    ESCALATION   // 人工交接：投诉、明确要求转人工
}
