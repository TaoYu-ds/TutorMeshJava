package com.tutormesh.knowledge;

import com.tutormesh.config.TutorMeshProperties;
import com.tutormesh.tool.KnowledgeToolManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.core.ResolvableType;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTest {
    @TempDir Path directory;
    KnowledgeBaseService service;
    TutorMeshProperties properties;
    @BeforeEach void setup() {
        properties = new TutorMeshProperties(); properties.getStorage().setKnowledgePath(directory.resolve("kb.json").toString());
        service = new KnowledgeBaseService(properties, new SemanticKnowledgeIndex(new DefaultListableBeanFactory().getBeanProvider(dev.langchain4j.model.embedding.EmbeddingModel.class), new DefaultListableBeanFactory().getBeanProvider(ResolvableType.forClassWithGenerics(EmbeddingStore.class, TextSegment.class))), new ObjectMapper());
    }
    @Test void unicodeAndOverlapStayBoundedAndCoverTail() {
        String text = "你好😀世界。".repeat(160) + "尾段🧠";
        List<String> chunks = new TextChunker(500, 80).split(text);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.getLast().endsWith("尾段🧠"));
        for (String chunk : chunks) {
            assertTrue(chunk.codePointCount(0, chunk.length()) <= 500);
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
        }
        assertEquals(List.of(), new TextChunker(500, 80).split(" \n"));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(80, 80));
    }
    @Test void reimportReplacesAllStaleChunksAndPersists() {
        service.addDocuments(List.of(Map.of("title", "导数", "content", "导数单调性判定步骤。".repeat(200))));
        assertTrue(service.docCount() > 1);
        assertEquals(1, service.addDocuments(List.of(Map.of("title", "导数", "content", "导数与单调性的关系需要先求导"))));
        assertEquals(1, service.docCount());
        KnowledgeBaseService loaded = new KnowledgeBaseService(properties, new SemanticKnowledgeIndex(new DefaultListableBeanFactory().getBeanProvider(dev.langchain4j.model.embedding.EmbeddingModel.class), new DefaultListableBeanFactory().getBeanProvider(ResolvableType.forClassWithGenerics(EmbeddingStore.class, TextSegment.class))), new ObjectMapper());
        loaded.init(); assertEquals(1, loaded.docCount());
        assertEquals("导数与单调性的关系需要先求导", loaded.search("导数", 3).getFirst().content());
    }
    @Test void explicitAndTitleIdentitiesCannotCollide() {
        service.addDocuments(List.of(Map.of("title", "title:政策", "content", "标题身份"), Map.of("title", "政策", "document_id", "title:政策", "content", "显式身份")));
        assertEquals(2, service.docCount());
        service.addDocuments(List.of(Map.of("title", "政策改名", "document_id", "title:政策", "content", "新显式身份")));
        assertEquals(2, service.docCount());
    }
    @Test void invalidBatchDoesNotPartiallyWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.addDocuments(List.of(Map.of("title", "有效", "content", "内容"), Map.of("title", "", "content", "坏数据"))));
        assertEquals(0, service.docCount());
    }
    @Test void cachedRecallObservesNewDocumentVersion() {
        service.addDocuments(List.of(Map.of("title", "导数", "content", "导数旧讲义")));
        var tools = new KnowledgeToolManager(service, (s,u,t,m) -> "invalid JSON", new ObjectMapper());
        assertEquals("导数旧讲义", tools.search("导数", 1).data().getFirst().content());
        assertTrue(tools.search("导数", 1).cached());
        service.addDocuments(List.of(Map.of("title", "导数", "content", "导数新讲义")));
        assertEquals("导数新讲义", tools.search("导数", 1).data().getFirst().content());
        assertFalse(tools.searchWithRewrite("导数", 1).reranked());
    }
}
