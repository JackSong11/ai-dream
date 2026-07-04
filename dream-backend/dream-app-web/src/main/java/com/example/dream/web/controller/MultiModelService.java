package com.example.dream.web.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
public class MultiModelService {

    private static final Logger logger = LoggerFactory.getLogger(MultiModelService.class);

    public void multiClientFlow() {
        try {
            // Create a new OpenAiChatModel for Groq (Llama3)
            OpenAiChatModel groqModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(System.getenv("GROQ_API_KEY"))
                    .model("llama3-70b-8192")
                    .temperature(0.5)
                    .build())
                .build();

            // Create a new OpenAiChatModel for GPT-4
            OpenAiChatModel gpt4Model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                    .baseUrl("https://api.openai.com")
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .model("gpt-4")
                    .temperature(0.7)
                    .build())
                .build();

            // Simple prompt for both models
            String prompt = "What is the capital of France?";

            String groqResponse = ChatClient.builder(groqModel).build().prompt(prompt).call().content();
            String gpt4Response = ChatClient.builder(gpt4Model).build().prompt(prompt).call().content();

            logger.info("Groq (Llama3) response: {}", groqResponse);
            logger.info("OpenAI GPT-4 response: {}", gpt4Response);
        }
        catch (Exception e) {
            logger.error("Error in multi-client flow", e);
        }
    }
}