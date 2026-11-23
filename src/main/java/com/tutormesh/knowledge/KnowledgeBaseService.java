package com.tutormesh.knowledge;

import com.tutormesh.config.TutorMeshProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    @org.springframework.beans.factory.annotation.Value("${KNOWLEDGE_SEED_DEMO:true}") private boolean seedDemo = true;
    @org.springframework.beans.factory.annotation.Value("${TUTORMESH_ENV:development}") private String environment = "development";
    private final TutorMeshProperties properties;
    private final SemanticKnowledgeIndex semanticIndex;
    private final ObjectMapper objectMapper;
    private record IndexSnapshot(long revision, List<KnowledgeDocument> documents,
            Map<String, Map<String, Integer>> frequencies, Map<String, Integer> lengths,
            Map<String, Integer> documentFrequencies, double averageLength) {}
    private volatile IndexSnapshot index = new IndexSnapshot(0, List.of(), Map.of(), Map.of(), Map.of(), 1);
    private final TextChunker splitter = new TextChunker(500, 80);

    public KnowledgeBaseService(TutorMeshProperties properties, SemanticKnowledgeIndex semanticIndex, ObjectMapper objectMapper) {
        this.properties = properties;
        this.semanticIndex = semanticIndex;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadPersistedDocuments();
        if (index.documents().isEmpty() && seedDemo && !environment.equalsIgnoreCase("production")) {
            addDocuments(defaultDocuments());
        }
    }

    public synchronized int addDocuments(List<Map<String, String>> inputDocs) {
        Map<String, List<KnowledgeDocument>> prepared = new LinkedHashMap<>();
        Map<String, String> legacyTitles = new HashMap<>();
        for (Map<String, String> input : inputDocs) {
            String title = input.get("title"), content = input.get("content"), explicit = input.get("document_id");
            if (title == null || title.isBlank() || content == null || content.isBlank())
                throw new IllegalArgumentException("文档标题和内容不能为空");
            if (explicit != null && explicit.isBlank()) throw new IllegalArgumentException("document_id 不能为空");
            String identity = com.tutormesh.util.Hashing.key(explicit == null ? "title" : "explicit", explicit == null ? title : explicit);
            String version = com.tutormesh.util.Hashing.sha256(content);
            List<String> chunks = splitter.split(content);
            List<KnowledgeDocument> replacement = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("title", title);
                metadata.put("document_id", identity);
                metadata.put("version", version);
                metadata.put("chunk_index", i);
                metadata.put("total_chunks", chunks.size());
                replacement.add(new KnowledgeDocument(identity + ":" + i, title, chunks.get(i), i, metadata, embed(chunks.get(i))));
            }
            prepared.put(identity, replacement);
            if (explicit == null) legacyTitles.put(identity, title);
        }
        List<KnowledgeDocument> next = new ArrayList<>(index.documents());
        prepared.forEach((identity, replacement) -> {
            next.removeIf(existing -> identity.equals(existing.metadata().get("document_id"))
                    || (!existing.metadata().containsKey("document_id") && existing.title().equals(legacyTitles.get(identity))));
            next.addAll(replacement);
        });
        // Persist before publishing the new snapshot: failed imports cannot report success.
        persistDocuments(next);
        IndexSnapshot nextIndex = buildIndex(index.revision() + 1, next);
        index = nextIndex;
        semanticIndex.refresh(nextIndex.documents(), nextIndex.revision());
        return prepared.values().stream().mapToInt(List::size).sum();
    }

    public long revision() { return index.revision(); }

    public List<SearchResult> search(String query, int topK) {
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("top_k 必须在 1 到 20 之间");
        if (query == null || query.isBlank()) {
            return List.of();
        }
        IndexSnapshot snapshot = index;
        Map<String, Double> bm25 = bm25Scores(query, snapshot);
        Map<String, Double> semantic = semanticIndex.scores(query, 20, snapshot.revision());
        Map<String, Double> vector = semantic == null ? vectorScores(query, snapshot.documents()) : normalize(semantic);
        Map<String, Double> fused = new HashMap<>();
        snapshot.documents().forEach(doc -> {
            double score = properties.getRag().getBm25Weight() * bm25.getOrDefault(doc.id(), 0.0)
                    + properties.getRag().getVectorWeight() * vector.getOrDefault(doc.id(), 0.0);
            fused.put(doc.id(), score);
        });
        return snapshot.documents().stream()
                .sorted(Comparator.comparingDouble((KnowledgeDocument d) -> fused.getOrDefault(d.id(), 0.0)).reversed())
                .limit(topK)
                .map(doc -> new SearchResult(
                        doc.id(),
                        doc.title(),
                        doc.content(),
                        round(fused.getOrDefault(doc.id(), 0.0)),
                        doc.chunkIndex(),
                        doc.metadata()
                ))
                .filter(result -> result.score() > 0.0)
                .toList();
    }

    public int docCount() {
        return index.documents().size();
    }

    private void loadPersistedDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<StoredKnowledgeDocument> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            List<KnowledgeDocument> loaded = new ArrayList<>();
            for (StoredKnowledgeDocument item : stored) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                KnowledgeDocument doc = new KnowledgeDocument(
                        item.id() == null || item.id().isBlank() ? md5(item.title() + item.chunkIndex() + item.content()) : item.id(),
                        item.title() == null || item.title().isBlank() ? "未命名文档" : item.title(),
                        item.content(),
                        item.chunkIndex(),
                        item.metadata() == null ? Map.of() : item.metadata(),
                        embed(item.content())
                );
                loaded.removeIf(existing -> existing.id().equals(doc.id()));
                loaded.add(doc);
            }
            index = buildIndex(index.revision() + 1, loaded);
            semanticIndex.refresh(index.documents(), index.revision());
            log.info("Loaded {} persisted knowledge chunks from {}", loaded.size(), path);
        } catch (Exception ex) {
            throw new IllegalStateException("知识库文件损坏或无法读取，请先恢复数据文件：" + path, ex);
        }
    }

    private void persistDocuments(List<KnowledgeDocument> snapshot) {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<StoredKnowledgeDocument> stored = snapshot.stream()
                    .map(doc -> new StoredKnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata()))
                    .toList();
            com.tutormesh.util.AtomicJson.write(objectMapper, path, stored);
        } catch (Exception ex) {
            throw new IllegalStateException("知识库持久化失败", ex);
        }
    }

    private IndexSnapshot buildIndex(long revision, List<KnowledgeDocument> documents) {
        Map<String, Map<String, Integer>> frequencies = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>(), documentFrequencies = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            List<String> terms = tokenize(doc.content());
            Map<String, Integer> counts = new HashMap<>();
            terms.forEach(term -> counts.merge(term, 1, Integer::sum));
            frequencies.put(doc.id(), Map.copyOf(counts)); lengths.put(doc.id(), terms.size());
            counts.keySet().forEach(term -> documentFrequencies.merge(term, 1, Integer::sum));
        }
        double average = lengths.values().stream().mapToInt(Integer::intValue).average().orElse(1);
        return new IndexSnapshot(revision, List.copyOf(documents), Map.copyOf(frequencies), Map.copyOf(lengths),
                Map.copyOf(documentFrequencies), Math.max(1, average));
    }

    private Map<String, Double> bm25Scores(String query, IndexSnapshot snapshot) {
        List<String> queryTerms = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeDocument doc : snapshot.documents()) {
            var terms = snapshot.frequencies().get(doc.id());
            double score = 0;
            for (String term : queryTerms) {
                int tf = terms.getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = snapshot.documentFrequencies().getOrDefault(term, 0);
                double idf = Math.log(1 + (snapshot.documents().size() - df + 0.5) / (df + 0.5));
                double denominator = tf + 1.5 * (1 - 0.75 + 0.75 * snapshot.lengths().get(doc.id()) / snapshot.averageLength());
                score += idf * (tf * 2.5) / denominator;
            }
            scores.put(doc.id(), score);
        }
        return normalize(scores);
    }

    private Map<String, Double> vectorScores(String query, List<KnowledgeDocument> documents) {
        double[] queryVec = embed(query);
        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeDocument doc : documents) scores.put(doc.id(), Math.max(0.0, cosine(queryVec, doc.embedding())));
        return normalize(scores);
    }

    private Map<String, Double> normalize(Map<String, Double> scores) {
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0.0) {
            return scores;
        }
        Map<String, Double> normalized = new HashMap<>();
        scores.forEach((id, score) -> normalized.put(id, score / max));
        return normalized;
    }

    private List<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n).trim();
                if (!gram.isBlank()) {
                    tokens.add(gram);
                }
            }
        }
        return tokens;
    }

    private double[] embed(String text) {
        double[] vector = new double[256];
        Set<String> grams = new HashSet<>(tokenize(text));
        for (String gram : grams) {
            int hash = gram.hashCode();
            int idx = Math.floorMod(hash, vector.length);
            vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private List<Map<String, String>> defaultDocuments() {
        List<Map<String, String>> docs = new ArrayList<>();
        docs.add(doc("函数单调性与导数的关系", "函数在某区间上单调递增，等价于该区间内导函数恒大于等于 0，且等号只在有限个点成立；单调递减则导函数恒小于等于 0。判断步骤：先求定义域，再求导，令导数为零求出分界点，最后按区间判断导数符号。注意：导数为零的点不一定是极值点，例如 f(x)=x³ 在 x=0 处导数为 0，但函数仍然单调递增。"));
        docs.add(doc("导数的定义与几何意义", "导数 f'(x₀) 是函数在 x₀ 处的瞬时变化率，几何意义是曲线在该点切线的斜率。定义式为 lim(Δx→0)[f(x₀+Δx)-f(x₀)]/Δx。切线方程为 y-f(x₀)=f'(x₀)(x-x₀)。常见应用：求切线、判断单调性、求极值与最值、证明不等式。可导必连续，连续不一定可导。"));
        docs.add(doc("牛顿第二定律与受力分析", "牛顿第二定律表达式为 F合=ma，物体加速度的方向与合外力方向相同。适用条件：宏观低速、惯性参考系。解题步骤：确定研究对象，画受力分析图（重力、支持力、摩擦力、拉力），建立坐标系分解力，求合力后代人 F合=ma。常见错误：漏掉摩擦力；把作用力与反作用力当成平衡力（二者作用在不同物体上，不能抵消）。"));
        docs.add(doc("电磁感应与楞次定律", "楞次定律：感应电流的磁场总要阻碍引起感应电流的磁通量的变化。判断步骤：确定原磁场方向，判断磁通量是增加还是减少，再由阻碍变化的原则确定感应电流磁场方向，最后用安培定则判断电流方向。法拉第电磁感应定律 E=nΔΦ/Δt。导体棒切割磁感线时 E=BLv。"));
        docs.add(doc("英语时态体系与常见混淆", "一般过去时表示过去某个时间发生并已结束的动作，常与 yesterday、last week、in 2020 等时间状语连用；现在完成时表示过去发生的动作对现在造成的影响，或从过去持续到现在的状态，常与 already、yet、since、for 连用。判断要点：句中有明确的过去时间点用一般过去时；强调对现在的影响或经历用现在完成时。"));
        docs.add(doc("定语从句关系词的选择", "关系代词 that/which/who/whom/whose 在从句中作主语或宾语；关系副词 when/where/why 在从句中作状语。只能用 that 的情况：先行词为 all、everything、nothing 等不定代词，或被最高级、序数词修饰时。非限制性定语从句不能用 that。判断步骤：先找先行词，再看从句缺什么成分，缺主语或宾语用关系代词，缺状语用关系副词。"));
        docs.add(doc("高考报名与成绩查询流程（通用说明）", "高考报名一般由本省教育考试院统一组织，流程为：网上注册 → 填写信息 → 现场或线上确认 → 缴费。成绩查询在本省教育考试院官网或指定平台进行，需要准考证号和身份证号。具体报名时间、缴费标准、查分入口每年由各省发布公告，本知识库不记录具体日期，请以当年官方通知为准。"));
        docs.add(doc("课程体系说明：同步课、提升课与冲刺课", "同步课跟随校内进度，覆盖基础概念与常规题型，适合基础薄弱或刚接触该章节的学生；提升课在同步课基础上做综合题与变式训练，适合已掌握基础、目标提分的学生；冲刺课按专题集中突破，配合套卷模拟，适合考前一到三个月。选课时先确认年级、当前水平和目标，不推荐明显跟不上的班型。"));
        return docs;
    }

    private Map<String, String> doc(String title, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("content", content);
        return map;
    }

    private record StoredKnowledgeDocument(
            String id,
            String title,
            String content,
            int chunkIndex,
            Map<String, Object> metadata
    ) {
    }
}
