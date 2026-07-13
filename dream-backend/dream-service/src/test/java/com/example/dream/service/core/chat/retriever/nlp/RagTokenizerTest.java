package com.example.dream.service.core.chat.retriever.nlp;

public class RagTokenizerTest {
    public static void main(String[] args) {
        RagTokenizer tokenizer = RagTokenizer.getInstance();

        // Test 1: Chinese text
        String result1 = tokenizer.tokenize("公开征求意见稿提出境外投资者可使用自有人民币或外汇投资");
        System.out.println("Test 1 - Chinese: " + result1);
        boolean hasChinese1 = result1.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count() > 0;
        System.out.println("  Has Chinese: " + hasChinese1);

        // Test 2: Mixed Chinese + English
        String result2 = tokenizer.tokenize("南京市长江大桥hello world");
        System.out.println("Test 2 - Mixed: " + result2);
        boolean hasChinese2 = result2.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count() > 0;
        System.out.println("  Has Chinese: " + hasChinese2);

        // Test 3: English only
        String result3 = tokenizer.tokenize("hello world this is a test");
        System.out.println("Test 3 - English: " + result3);

        // Test 4: Long Chinese text
        String result4 = tokenizer.tokenize("多校划片就是一个小区对应多个小学初中，让买了学区房的家庭也不确定到底能上哪个学校。目的是通过这种方式为学区房降温，把就近入学落到实处。南京市长江大桥");
        System.out.println("Test 4 - Long Chinese: " + result4);
        boolean hasChinese4 = result4.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count() > 0;
        System.out.println("  Has Chinese: " + hasChinese4);
    }
}