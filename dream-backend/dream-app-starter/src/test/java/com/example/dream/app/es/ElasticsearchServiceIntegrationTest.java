package com.example.dream.app.es;

import com.example.dream.integration.service.es.ElasticsearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elasticsearch 常用操作集成测试。
 * <p>覆盖索引管理、文档 CRUD、批量写入及多种搜索场景。</p>
 * <p>运行前置条件：本地或指定地址存在可用的 Elasticsearch 服务（默认 127.0.0.1:9200）。
 * 若无 ES 环境，可在 CI 中排除本测试。</p>
 *
 * @author dream
 */
@SpringBootTest
@DisplayName("Elasticsearch 常用操作集成测试")
class ElasticsearchServiceIntegrationTest {

    private static final String INDEX = "test_product_index";

    @Autowired
    private ElasticsearchService esService;

    @BeforeEach
    void setUp() throws InterruptedException {
        // 保证测试前索引为干净状态
        if (esService.indexExists(INDEX)) {
            esService.deleteIndex(INDEX);
        }
        assertTrue(esService.createIndex(INDEX), "创建索引应成功");
    }

    @AfterEach
    void tearDown() {
        if (esService.indexExists(INDEX)) {
            esService.deleteIndex(INDEX);
        }
    }

    @Test
    @DisplayName("索引存在性判断")
    void testIndexExists() {
        assertTrue(esService.indexExists(INDEX), "刚创建的索引应存在");
        assertFalse(esService.indexExists("index_never_exists_xyz"), "未创建的索引应不存在");
    }

    @Test
    @DisplayName("文档新增与根据ID查询")
    void testSaveAndGet() {
        EsProduct product = new EsProduct("1", "iPhone 15", "phone", 6999.0);
        String id = esService.saveDocument(INDEX, "1", product);
        assertEquals("1", id, "返回的文档ID应一致");

        EsProduct fetched = esService.getDocument(INDEX, "1", EsProduct.class);
        assertNotNull(fetched, "应能查到文档");
        assertEquals("iPhone 15", fetched.getName());
        assertEquals(6999.0, fetched.getPrice());
    }

    @Test
    @DisplayName("文档局部更新")
    void testUpdate() throws InterruptedException {
        esService.saveDocument(INDEX, "1", new EsProduct("1", "iPhone 15", "phone", 6999.0));

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("price", 5999.0);
        boolean updated = esService.updateDocument(INDEX, "1", partial);
        assertTrue(updated, "更新应成功");

        EsProduct fetched = esService.getDocument(INDEX, "1", EsProduct.class);
        assertEquals(5999.0, fetched.getPrice(), "价格应被更新");
        assertEquals("iPhone 15", fetched.getName(), "未更新字段应保留");
    }

    @Test
    @DisplayName("文档删除")
    void testDelete() {
        esService.saveDocument(INDEX, "1", new EsProduct("1", "iPhone 15", "phone", 6999.0));
        assertTrue(esService.deleteDocument(INDEX, "1"), "删除应成功");
        assertNull(esService.getDocument(INDEX, "1", EsProduct.class), "删除后应查不到");
    }

    @Test
    @DisplayName("批量写入与matchAll查询")
    void testBulkSaveAndMatchAll() throws InterruptedException {
        Map<String, EsProduct> docs = new LinkedHashMap<>();
        docs.put("1", new EsProduct("1", "iPhone 15", "phone", 6999.0));
        docs.put("2", new EsProduct("2", "MacBook Pro", "laptop", 14999.0));
        docs.put("3", new EsProduct("3", "iPad Air", "tablet", 4799.0));
        assertTrue(esService.bulkSave(INDEX, docs), "批量写入应成功");

        // ES 近实时，稍等刷新
        Thread.sleep(1500);

        List<EsProduct> all = esService.matchAll(INDEX, EsProduct.class);
        assertEquals(3, all.size(), "matchAll 应查到 3 条");
    }

    @Test
    @DisplayName("term精确查询与match全文检索")
    void testSearch() throws InterruptedException {
        Map<String, EsProduct> docs = new LinkedHashMap<>();
        docs.put("1", new EsProduct("1", "iPhone 15 Pro", "phone", 8999.0));
        docs.put("2", new EsProduct("2", "MacBook Pro", "laptop", 14999.0));
        docs.put("3", new EsProduct("3", "iPhone 15", "phone", 6999.0));
        esService.bulkSave(INDEX, docs);
        Thread.sleep(1500);

        // term 精确匹配 keyword 字段（category 默认动态映射会生成 category.keyword）
        List<EsProduct> phones = esService.searchByTerm(INDEX, "category.keyword", "phone", EsProduct.class);
        assertEquals(2, phones.size(), "term 应查到 2 条 phone");

        // match 全文检索 name 字段
        List<EsProduct> iphones = esService.searchByMatch(INDEX, "name", "iPhone", EsProduct.class);
        assertEquals(2, iphones.size(), "match 应查到 2 条包含 iPhone 的文档");
    }

    private static void assertNull(Object obj, String message) {
        org.junit.jupiter.api.Assertions.assertNull(obj, message);
    }
}