package com.tutormesh.tool;

import com.tutormesh.agent.*;
import com.tutormesh.intent.IntentCategory;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 教学角色的确定性工具。
 *
 * <p>设计原则：这些工具不调用模型、不访问外部系统，只把「可以确定判断的事」固化成代码，
 * 从而把教学约束（不代写、不编造日期、负荷校验）变成可测试的行为，而不是靠 prompt 自觉。
 * 请求数据全部由应用侧传入，不接受模型伪造的参数。
 */
@Component
public class RoleToolRegistration {
    private final ToolRegistry registry;
    public RoleToolRegistration(ToolRegistry registry) { this.registry = registry; }

    @PostConstruct public void register() {
        // ── 通用：上下文自查与澄清字段建议 ──
        register("general", "inspect_request_context", "查看本轮意图、紧急度、已知实体及上下文是否可用；不查询外部系统。",
                Map.of("focus", text(40)), List.of(), (request, args) -> Map.of(
                        "intent", intent(request), "intent_group", Objects.toString(request.intentGroup(), "other"),
                        "urgency", request.urgency() == null ? "LOW" : request.urgency().name(), "intent_confidence", request.intentConfidence(),
                        "entities", entities(request), "context_available", request.context() != null && !request.context().isBlank(),
                        "requested_focus", args.getOrDefault("focus", "general")));
        register("general", "suggest_required_fields", "根据意图和本轮已知实体，建议下一轮仅补充必要字段。",
                Map.of(), List.of(), (request, args) -> requiredFields(request));

        // ── 讲题：卡点归类 + 讲解脚手架 ──
        register("tutor", "classify_blocker", "把学生的卡点归到概念不清/计算失误/审题偏差/步骤跳跃/迁移困难五类，并给出对应讲解策略；不直接给答案。",
                Map.of("blocker", text(200)), List.of("blocker"), (request, args) -> classifyBlocker((String) args.get("blocker")));
        register("tutor", "build_explanation_plan", "按卡点类型生成分层讲解步骤（思路→关键步骤→自检），不输出最终答案。",
                Map.of("blocker_type", text(20), "subject", text(20)), List.of("blocker_type"),
                (request, args) -> explanationPlan(args));

        // ── 规划：负荷校验 ──
        register("plan", "check_study_load", "按每天可投入时长、任务数和可用天数估算单任务时长，判断负荷是否合理；不替学生做决定。",
                Map.of("daily_minutes", Map.of("type", "integer", "minimum", 10, "maximum", 1440),
                        "task_count", Map.of("type", "integer", "minimum", 1, "maximum", 200),
                        "days", Map.of("type", "integer", "minimum", 1, "maximum", 365)),
                List.of("daily_minutes", "task_count", "days"), (request, args) -> studyLoad(args));

        // ── 考试：官方渠道 ──
        register("exam", "exam_official_channels", "给出某类考试的官方信息渠道；不提供具体日期与分数线，避免过期或地区差异误导。",
                Map.of("exam", text(20)), List.of("exam"), (request, args) -> officialChannels((String) args.get("exam")));

        // ── 交接 ──
        register("escalation", "create_handoff_summary", "整理本轮意图、优先级和已知实体，不创建真实工单。",
                Map.of("reason", text(120)), List.of(), (request, args) -> HandoffSummary.create(request,
                        Objects.toString(args.get("reason"), "需要老师进一步确认")));
    }

    private void register(String role, String name, String description, Map<String, Object> properties, List<String> required,
                          ToolDefinition.ContextHandler handler) {
        registry.register(ToolDefinition.contextual(name, description, Map.of("type", "object", "properties", properties,
                "required", required, "additionalProperties", false), Set.of(role), handler));
    }

    private Map<String, Object> text(int maximum) { return Map.of("type", "string", "maxLength", maximum); }
    private Map<String, List<String>> entities(AgentRequest request) { return request.entities() == null ? Map.of() : request.entities(); }
    private String intent(AgentRequest request) { return request.intent() == null ? "other" : request.intent().name().toLowerCase(Locale.ROOT); }
    private boolean known(AgentRequest request, String field) { return !entities(request).getOrDefault(field, List.of()).isEmpty(); }

    /** 按意图给出「还缺哪些必要信息」，只问最少的问题，避免一次追问一堆。 */
    private Map<String, Object> requiredFields(AgentRequest request) {
        List<String> fields = new ArrayList<>();
        IntentCategory intent = request.intent() == null ? IntentCategory.OTHER : request.intent();
        switch (intent) {
            case KNOWLEDGE_QUERY -> {
                if (!known(request, "subject")) fields.add("学科");
                if (!known(request, "chapter")) fields.add("具体章节或知识点");
            }
            case HOMEWORK_HELP -> {
                if (!known(request, "subject")) fields.add("学科");
                fields.add("题目原文或卡在哪一步");
            }
            case STUDY_PLAN -> {
                if (!known(request, "exam")) fields.add("目标考试或学习目标");
                fields.add("距离目标还有多久");
                fields.add("每天大概能投入多长时间");
            }
            case EXAM_INFO -> {
                if (!known(request, "exam")) fields.add("是哪类考试（高考/中考/四六级/期末）");
                fields.add("所在地区或学校");
            }
            case COURSE_RECOMMEND -> {
                if (!known(request, "grade")) fields.add("年级");
                fields.add("当前水平和目标");
            }
            case COMPLAINT, REQUEST -> {
                if (!known(request, "date")) fields.add("发生时间");
                fields.add("期望的处理方式");
            }
            case OTHER -> fields.add("希望解决的具体问题");
            default -> { }
        }
        return Map.of("intent", intent(request), "required_fields", fields, "known_entities", entities(request));
    }

    /**
     * 卡点归类：把「不会做」这种模糊表述映射到五类可干预的原因。
     * 不同卡点对应不同讲解策略，这是分层讲解的前提。
     */
    private Map<String, Object> classifyBlocker(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String type;
        List<String> strategies;
        if (containsAny(text, "定义", "不懂", "为什么", "原理", "区别", "概念", "理解")) {
            type = "概念不清";
            strategies = List.of("先用一句话重述这个概念在解决什么问题", "给一个正例和一个反例", "再回到题目指出用到的是哪一条");
        } else if (containsAny(text, "算错", "计算", "结果不对", "符号", "代入", "化简")) {
            type = "计算失误";
            strategies = List.of("让学生逐步写出每一步的中间结果", "标记最容易出错的符号与单位", "用反向代入验证结果");
        } else if (containsAny(text, "看错", "理解错", "没读懂", "要求", "审题")) {
            type = "审题偏差";
            strategies = List.of("圈出题目的已知条件和所求", "区分题目问的是哪一个量", "复述题目再用自己的话改写");
        } else if (containsAny(text, "跳步", "中间步骤", "不知道怎么下手", "第一步", "思路")) {
            type = "步骤跳跃";
            strategies = List.of("把题目拆成三个可独立完成的小问", "先写出已知与目标之间的桥梁公式", "从目标倒推需要的条件");
        } else if (containsAny(text, "换个题", "举一反三", "变式", "稍改", "迁移")) {
            type = "迁移困难";
            strategies = List.of("归纳这类题的共同结构", "做一道只改数字的同类题", "做一道改条件的变式题");
        } else {
            type = "待确认";
            strategies = List.of("请学生描述已经尝试到哪一步", "请学生指出具体卡住的位置");
        }
        return Map.of("blocker_type", type, "strategies", strategies,
                "answer_given", false, "note", "只做卡点归类，不代替学生完成题目");
    }

    /** 讲解脚手架：固定「思路 → 关键步骤 → 自检」三段，确保不直接给答案。 */
    private Map<String, Object> explanationPlan(Map<String, Object> args) {
        String type = Objects.toString(args.get("blocker_type"), "待确认");
        String subject = Objects.toString(args.get("subject"), "该学科");
        List<String> steps = new ArrayList<>(List.of(
                "复述题目与已知条件（" + subject + "）",
                "指出这个卡点对应的知识点",
                "给思路：这一步要达成什么目标",
                "给关键步骤：只给到能自己往下算的位置",
                "给自检清单：结果代回、单位、边界条件"));
        return Map.of("blocker_type", type, "subject", subject, "explanation_steps", steps,
                "final_answer_included", false);
    }

    /** 负荷校验：把「每天 60 分钟做完 5 个任务、还有 7 天」变成可判断的结论。 */
    private Map<String, Object> studyLoad(Map<String, Object> args) {
        int daily = Integer.parseInt(args.get("daily_minutes").toString());
        int tasks = Integer.parseInt(args.get("task_count").toString());
        int days = Integer.parseInt(args.get("days").toString());
        int totalMinutes = daily * days;
        int perTaskMinutes = tasks == 0 ? 0 : totalMinutes / tasks;
        boolean tight = perTaskMinutes < 15;
        boolean overload = perTaskMinutes < 8;
        String advice = overload
                ? "按当前时间分配，每个任务不到 8 分钟，建议减少任务数或延长周期"
                : tight ? "每个任务约 " + perTaskMinutes + " 分钟，偏紧，建议优先保证薄弱章节"
                : "负荷合理，可按计划推进";
        return Map.of("daily_minutes", daily, "task_count", tasks, "days", days,
                "total_minutes", totalMinutes, "per_task_minutes", perTaskMinutes,
                "load_ok", !overload, "advice", advice);
    }

    /** 考试官方渠道：只给渠道与流程，不给具体日期和分数线。 */
    private Map<String, Object> officialChannels(String value) {
        String exam = value == null ? "" : value.strip();
        List<String> channels = switch (exam) {
            case "高考" -> List.of("本省教育考试院官网", "教育部阳光高考平台");
            case "中考" -> List.of("本市教育局或招生考试院官网", "就读学校教务通知");
            case "四六级", "四级", "六级", "四六级考试" -> List.of("全国大学英语四六级考试官网（cet.neea.edu.cn）", "学校教务处通知");
            case "期末", "期中" -> List.of("学校教务系统", "任课老师或辅导员通知");
            case "考研", "考研初试" -> List.of("中国研究生招生信息网（yz.chsi.com.cn）", "目标院校研究生院官网");
            default -> List.of("学校教务系统", "对应考试的官方报名网站");
        };
        return Map.of("exam", exam.isBlank() ? "未指明" : exam, "channels", channels,
                "date_guaranteed", false,
                "note", "具体报名时间、考场和分数线以官方最新公告为准，这里不提供具体日期以免过期误导");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
