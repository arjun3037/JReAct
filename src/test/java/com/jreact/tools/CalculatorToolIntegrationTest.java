package com.jreact.tools;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the model itself decides to call CalculatorTool (rather than
 * computing the answer from its own weights) and that the manual
 * tool-calling loop wires the result back correctly. This is a single
 * hand-driven iteration of what AgentLoopService will later automate.
 */
@SpringBootTest
class CalculatorToolIntegrationTest {

    @Autowired
    private OpenAiChatModel chatModel;

    @Test
    void modelUsesCalculatorToolForArithmetic() {
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(new CalculatorTool()))
                .build();

        Prompt prompt = new Prompt(
                List.of(new UserMessage(
                        "What is 12 times the sum of 3 and 4? Use the calculate tool rather than computing it yourself.")),
                options);

        ChatResponse response = chatModel.call(prompt);

        assertThat(response.hasToolCalls())
                .as("model should have requested the calculate tool instead of answering directly")
                .isTrue();

        while (response.hasToolCalls()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(result.conversationHistory(), options);
            response = chatModel.call(prompt);
        }

        String finalAnswer = response.getResult().getOutput().getText();
        System.out.println("Final answer: " + finalAnswer);
        assertThat(finalAnswer).contains("84");
    }
}
