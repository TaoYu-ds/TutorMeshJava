package com.tutormesh.intent;

import com.tutormesh.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三路融合的意图识别器：LLM 语义分类（0.70）+ 字符 n-gram 相似度（0.20）+ 关键词规则（0.10）。
 *
 * <p>三路的强弱互补：LLM 能处理复杂表述但慢且贵，n-gram 相似度快但依赖示例覆盖，
 * 关键词规则最快最准但覆盖窄。加权投票后再做一次「细分类修正」——当模型给出宽泛大类、
 * 而关键词明确命中某个细分类时，以细分类为准。
 *
 * <p>置信度低于阈值时统一降级为 OTHER，避免把作业辅导误判成考试咨询这类高代价错误。
 */
@Service
public class IntentRecognizer {

    private static final double CONFIDENCE_THRESHOLD = 0.5;

    /** 每个意图的锚点示例，同时用于 few-shot 提示与 n-gram 相似度比对。 */
    private static final Map<IntentCategory, List<String>> TEMPLATES = Map.ofEntries(
            Map.entry(IntentCategory.QUERY, List.of("这门课用什么教材？", "在哪看我的学习记录？", "课程表在哪里查？")),
            Map.entry(IntentCategory.COMPLAINT, List.of("讲得太快跟不上", "这个问题问了三遍都没人回", "视频一直卡")),
            Map.entry(IntentCategory.REQUEST, List.of("帮我拆一下这道题的步骤", "把今天的知识点整理成提纲", "帮我把错题归类")),
            Map.entry(IntentCategory.GREETING, List.of("你好", "老师好", "在吗")),
            Map.entry(IntentCategory.ESCALATION, List.of("我要投诉", "能不能换个老师", "这个必须给我个说法")),
            Map.entry(IntentCategory.FEEDBACK, List.of("讲得很清楚", "这个方法有用", "谢谢老师")),
            Map.entry(IntentCategory.HUMAN_HANDOFF, List.of("转人工", "我要找老师", "帮我接人工")),
            Map.entry(IntentCategory.KNOWLEDGE_QUERY, List.of("导数的几何意义是什么", "牛顿第二定律怎么理解", "现在完成时和一般过去时的区别")),
            Map.entry(IntentCategory.HOMEWORK_HELP, List.of("这道题不会做", "帮我看看第三步错在哪", "这个作业怎么下手")),
            Map.entry(IntentCategory.STUDY_PLAN, List.of("帮我排一下这周的复习计划", "离期末还有两周怎么安排", "每天只有一小时怎么复习")),
            Map.entry(IntentCategory.EXAM_INFO, List.of("高考报名什么时候截止", "四级什么时候出分", "期末考试的考场在哪")),
            Map.entry(IntentCategory.COURSE_RECOMMEND, List.of("有什么适合高一的数学课", "想补一下物理力学", "有没有冲刺班"))
    );

    /** 细分类：命中后允许覆盖宽泛大类。 */
    private static final Set<IntentCategory> SPECIFIC_INTENTS = Set.of(
            IntentCategory.KNOWLEDGE_QUERY,
            IntentCategory.HOMEWORK_HELP,
            IntentCategory.STUDY_PLAN,
            IntentCategory.EXAM_INFO,
            IntentCategory.COURSE_RECOMMEND,
            IntentCategory.HUMAN_HANDOFF
    );

    /** 宽泛大类：容易被细分类修正。 */
    private static final Set<IntentCategory> GENERIC_INTENTS = Set.of(
            IntentCategory.QUERY,
            IntentCategory.REQUEST,
            IntentCategory.ESCALATION,
            IntentCategory.COMPLAINT,
            IntentCategory.FEEDBACK
    );

    /** 细分类到粗分组的映射，用于路由与统计。 */
    private static final Map<IntentCategory, IntentCategory> INTENT_GROUPS = Map.ofEntries(
            Map.entry(IntentCategory.KNOWLEDGE_QUERY, IntentCategory.QUERY),
            Map.entry(IntentCategory.HOMEWORK_HELP, IntentCategory.REQUEST),
            Map.entry(IntentCategory.STUDY_PLAN, IntentCategory.REQUEST),
            Map.entry(IntentCategory.EXAM_INFO, IntentCategory.QUERY),
            Map.entry(IntentCategory.COURSE_RECOMMEND, IntentCategory.QUERY),
            Map.entry(IntentCategory.HUMAN_HANDOFF, IntentCategory.ESCALATION)
    );

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private record CachedIntent(IntentResult value, Instant expires) {}
    private final Map<String, CachedIntent> cache = new ConcurrentHashMap<>();
    private final IntentEmbedding embedding;

    public IntentRecognizer(LlmGateway llmGateway, ObjectMapper objectMapper, IntentEmbedding embedding) {
        this.embedding = embedding;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public IntentResult recognize(String message, List<Map<String, String>> history) {
        String key = cacheKey(message, history);
        CachedIntent cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.expires())) {
            return cached.value();
        }
        Instant start = Instant.now();
        Map<String, Object> llm = llmRecognize(message, history);
        IntentEmbedding.Match match = embedding.match(message, TEMPLATES);
        Map<String, Object> semantic = Map.of("intent", match.intent(), "confidence", match.confidence());
        Map<String, Object> pattern = patternRecognize(message);
        VoteResult vote = vote(llm, semantic, pattern);
        IntentCategory intent = vote.intent();
        UrgencyLevel urgency = urgency(message, intent);
        IntentResult result = new IntentResult(
                intent,
                vote.confidence(),
                urgency,
                intentGroup(intent),
                extractEntities(message),
                String.valueOf(llm.getOrDefault("reasoning", "")),
                Duration.between(start, Instant.now()).toMillis(),
                vote.sourceScores(),
                match.source()
        );
        if (cache.size() > 1000) {
            cache.clear();
        }
        cache.put(key, new CachedIntent(result, Instant.now().plusSeconds(match.source().equals("local-ngram") ? 30 : 300)));
        return result;
    }

    private Map<String, Object> llmRecognize(String message, List<Map<String, String>> history) {
        StringBuilder examples = new StringBuilder();
        TEMPLATES.forEach((intent, samples) ->
                examples.append("消息: \"").append(samples.getFirst()).append("\" -> 意图: ")
                        .append(intent.name().toLowerCase(Locale.ROOT)).append('\n'));
        String prompt = """
                你是学习辅导场景的意图分析专家。根据示例判断学生这句话的意图，返回 JSON。
                优先返回细粒度意图，而不是宽泛大类。例如：
                - 问定义、原理、公式、区别 → knowledge_query
                - 说某道题不会、让看步骤错在哪 → homework_help
                - 问复习怎么安排、时间怎么分配 → study_plan
                - 问报名时间、查分、考场、分数线 → exam_info
                - 问该报什么课、哪个班适合 → course_recommend

                示例:
                %s
                最近对话:
                %s
                用户消息: "%s"
                返回格式: {"intent":"knowledge_query","confidence":0.9,"reasoning":"一句话说明"}
                可选意图: %s
                """.formatted(examples, history == null ? "" : history, message, allIntentNames());
        try {
            String raw = llmGateway.chat("", prompt, 0.1, 256);
            String json = sliceJsonObject(raw);
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (!(data.get("intent") instanceof String name) || !(data.get("confidence") instanceof Number score)
                    || !Double.isFinite(score.doubleValue()) || score.doubleValue() < 0 || score.doubleValue() > 1)
                throw new IllegalArgumentException("invalid_intent_response");
            IntentCategory category = parseIntent(name);
            if (category == IntentCategory.OTHER && !name.equalsIgnoreCase("other")) throw new IllegalArgumentException("invalid_intent_name");
            data.put("intent", category);
            return data;
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("intent", IntentCategory.OTHER);
            fallback.put("confidence", 0.0);
            fallback.put("reasoning", "LLM recognition failed");
            fallback.put("failed", true);
            return fallback;
        }
    }

    private Map<String, Object> semanticRecognize(String message) {
        IntentCategory best = IntentCategory.OTHER;
        double bestScore = 0.0;
        for (Map.Entry<IntentCategory, List<String>> entry : TEMPLATES.entrySet()) {
            for (String sample : entry.getValue()) {
                double score = jaccard(charNgrams(message), charNgrams(sample));
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
        }
        return Map.of("intent", best, "confidence", bestScore);
    }

    private Map<String, Object> patternRecognize(String message) {
        String msg = normalize(message);
        Map<IntentCategory, List<String>> specificPatterns = Map.ofEntries(
                Map.entry(IntentCategory.HUMAN_HANDOFF, List.of("转人工", "人工客服", "找老师", "接人工")),
                Map.entry(IntentCategory.KNOWLEDGE_QUERY, List.of("是什么", "定义", "原理", "公式", "区别", "怎么理解", "推导", "几何意义")),
                Map.entry(IntentCategory.HOMEWORK_HELP, List.of("不会做", "这题", "作业", "错在哪", "怎么解", "卡住", "看不懂", "没思路")),
                Map.entry(IntentCategory.STUDY_PLAN, List.of("计划", "安排", "复习", "规划", "时间表", "怎么分配", "几天", "每天")),
                Map.entry(IntentCategory.EXAM_INFO, List.of("报名", "考试时间", "查分", "分数线", "准考证", "什么时候考", "考场")),
                Map.entry(IntentCategory.COURSE_RECOMMEND, List.of("推荐", "报班", "适合", "补习", "哪个课", "冲刺班", "同步课"))
        );
        Map<String, Object> specific = bestPatternMatch(msg, specificPatterns);
        if (specific.get("intent") != IntentCategory.OTHER) {
            return specific;
        }

        Map<IntentCategory, List<String>> genericPatterns = Map.of(
                IntentCategory.ESCALATION, List.of("投诉", "经理", "换老师", "supervisor"),
                IntentCategory.COMPLAINT, List.of("太差", "听不懂", "讲太快", "没人管", "horrible"),
                IntentCategory.QUERY, List.of("?", "？", "怎么", "什么", "哪里"),
                IntentCategory.REQUEST, List.of("帮我", "能不能", "请", "please"),
                IntentCategory.GREETING, List.of("你好", "嗨", "hello", "hi"),
                IntentCategory.FEEDBACK, List.of("谢谢", "有用", "很好", "谢谢老师")
        );
        return bestPatternMatch(msg, genericPatterns);
    }

    private Map<String, Object> bestPatternMatch(String msg, Map<IntentCategory, List<String>> patterns) {
        IntentCategory best = IntentCategory.OTHER;
        double bestScore = 0.0;
        for (Map.Entry<IntentCategory, List<String>> entry : patterns.entrySet()) {
            long hits = entry.getValue().stream().filter(msg::contains).count();
            if (hits > 0) {
                double score = Math.min(1.0, 0.5 + 0.25 * (hits - 1));
                if (score > bestScore) {
                    bestScore = score;
                    best = entry.getKey();
                }
            }
        }
        return Map.of("intent", best, "confidence", bestScore);
    }

    private VoteResult vote(Map<String, Object> llm, Map<String, Object> semantic, Map<String, Object> pattern) {
        Map<String, Double> sourceScores = new LinkedHashMap<>();
        sourceScores.put("llm", confidence(llm));
        sourceScores.put("embedding", confidence(semantic));
        sourceScores.put("pattern", confidence(pattern));

        if (Boolean.TRUE.equals(llm.get("failed"))) {
            if (semantic.get("intent") != IntentCategory.OTHER && confidence(semantic) > 0) {
                return new VoteResult((IntentCategory) semantic.get("intent"), confidence(semantic), sourceScores);
            }
            if (pattern.get("intent") != IntentCategory.OTHER && confidence(pattern) > 0) {
                return new VoteResult((IntentCategory) pattern.get("intent"), confidence(pattern), sourceScores);
            }
            return new VoteResult(IntentCategory.OTHER, 0.0, sourceScores);
        }
        Map<IntentCategory, Double> scores = new EnumMap<>(IntentCategory.class);
        addScore(scores, llm, 0.70);
        addScore(scores, semantic, 0.20);
        addScore(scores, pattern, 0.10);
        Map.Entry<IntentCategory, Double> best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(Map.entry(IntentCategory.OTHER, 0.0));
        IntentCategory patternIntent = (IntentCategory) pattern.getOrDefault("intent", IntentCategory.OTHER);
        double patternConfidence = confidence(pattern);
        if (GENERIC_INTENTS.contains(best.getKey())
                && SPECIFIC_INTENTS.contains(patternIntent)
                && patternConfidence >= 0.5
                && best.getValue() < 0.8) {
            sourceScores.put("refined_by_pattern", patternConfidence);
            return new VoteResult(patternIntent, Math.max(best.getValue(), patternConfidence), sourceScores);
        }
        if (best.getValue() < CONFIDENCE_THRESHOLD) {
            return new VoteResult(IntentCategory.OTHER, best.getValue(), sourceScores);
        }
        return new VoteResult(best.getKey(), best.getValue(), sourceScores);
    }

    private void addScore(Map<IntentCategory, Double> scores, Map<String, Object> result, double weight) {
        IntentCategory intent = (IntentCategory) result.getOrDefault("intent", IntentCategory.OTHER);
        double confidence = confidence(result);
        scores.merge(intent, weight * confidence, Double::sum);
    }

    private double confidence(Map<String, Object> result) {
        return ((Number) result.getOrDefault("confidence", 0.0)).doubleValue();
    }

    /**
     * 抽取学习场景的结构化实体：学科、章节、年级、考试、时间。
     * 这些字段会进入路由打分（章节命中的题目更可能是讲题需求），也用于澄清时追问缺失信息。
     */
    private Map<String, List<String>> extractEntities(String message) {
        Map<String, List<String>> entities = new LinkedHashMap<>();
        entities.put("subject", regexFind(message, "(数学|物理|英语|化学|语文|生物|历史|地理|政治)"));
        entities.put("chapter", regexFind(message,
                "(函数|导数|三角函数|数列|立体几何|解析几何|概率|统计|不等式|力学|电磁学|牛顿|动量|能量|时态|从句|非谓语|定语|状语|完形|阅读|有机|无机|遗传|细胞)"));
        entities.put("grade", regexFind(message, "(高一|高二|高三|初一|初二|初三|大一|大二|大三|大四|考研)"));
        entities.put("exam", regexFind(message, "(高考|中考|四六级|四级|六级|期末|期中|月考|一模|二模|会考|考研初试)"));
        entities.put("date", regexFind(message,
                "(今天|明天|后天|昨天|本周|这周|下周|本月|下个月|寒假|暑假|\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?|\\d{1,2}月\\d{1,2}日)"));
        return entities;
    }

    private List<String> regexFind(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return unique(values);
    }

    private List<String> unique(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 四档紧急度。教育场景里「明天考试」「今晚要交作业」才是真正的高优先级，
     * 因此把时间紧迫性作为主信号，投诉与转人工单独抬到 HIGH。
     */
    private UrgencyLevel urgency(String message, IntentCategory intent) {
        String msg = normalize(message)
                .replaceAll("(?:不(?:是|用|需要|太)?|无需|无须|非|并不|没那么|暂不)\\s*(?:紧急|立刻|马上|尽快|快点)", "")
                .replaceAll("\\b(?:not|isn't|is not|no)\\s+(?:an?\\s+)?(?:emergency|urgent|asap|now)\\b", "");
        if (msg.contains("紧急") || msg.contains("立刻") || msg.contains("今晚要交") || englishUrgency(msg, "emergency|urgent|asap")) {
            return UrgencyLevel.CRITICAL;
        }
        if (msg.contains("今天") || msg.contains("明天") || msg.contains("马上") || msg.contains("尽快")
                || englishUrgency(msg, "hurry|now")
                || intent == IntentCategory.ESCALATION || intent == IntentCategory.HUMAN_HANDOFF) {
            return UrgencyLevel.HIGH;
        }
        if (intent == IntentCategory.COMPLAINT) {
            return UrgencyLevel.MEDIUM;
        }
        if (msg.contains("这周") || msg.contains("下周") || msg.contains("快点") || englishUrgency(msg, "soon")) return UrgencyLevel.MEDIUM;
        return UrgencyLevel.LOW;
    }

    private boolean englishUrgency(String text, String alternatives) {
        return java.util.regex.Pattern.compile("(?<![a-z])(?:" + alternatives + ")(?![a-z])").matcher(text).find();
    }

    private IntentCategory parseIntent(String value) {
        try {
            return IntentCategory.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return IntentCategory.OTHER;
        }
    }

    private String sliceJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "{}";
    }

    private List<String> charNgrams(String text) {
        String normalized = normalize(text);
        List<String> grams = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                grams.add(normalized.substring(i, i + n));
            }
        }
        return grams;
    }

    private double jaccard(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> a = new java.util.HashSet<>(left);
        java.util.Set<String> b = new java.util.HashSet<>(right);
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String cacheKey(String message, List<Map<String, String>> history) {
        try { return com.tutormesh.util.Hashing.key(message, objectMapper.writeValueAsString(history)); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid history", ex); }
    }

    private String intentGroup(IntentCategory intent) {
        return INTENT_GROUPS.getOrDefault(intent, intent).name().toLowerCase(Locale.ROOT);
    }

    private String allIntentNames() {
        List<String> names = new ArrayList<>();
        for (IntentCategory category : IntentCategory.values()) {
            names.add(category.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    private record VoteResult(IntentCategory intent, double confidence, Map<String, Double> sourceScores) {
    }
}
