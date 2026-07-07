package com.example.dream.app.es;

/**
 * ES 集成测试用的商品文档。
 * <p>测试模块未引入 lombok，故手写构造器与 getter/setter。</p>
 *
 * @author dream
 */
public class EsProduct {

    /** 商品 ID */
    private String id;

    /** 商品名称 */
    private String name;

    /** 分类 */
    private String category;

    /** 价格 */
    private Double price;

    public EsProduct() {
    }

    public EsProduct(String id, String name, String category, Double price) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }
}