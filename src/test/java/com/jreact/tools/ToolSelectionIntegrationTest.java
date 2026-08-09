package com.jreact.tools;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
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
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With ALL THREE tools registered, confirms the model picks the right one(s)
 * for the question rather than always calling every available tool — and,
 * for the dependent question, chains weather -> calculate in the correct
 * order (the spec's core "multi-step tool chaining" success criterion).
 */
@SpringBootTest
class ToolSelectionIntegrationTest {

    // Mirrors AgentLoopService's system prompt so this test reflects real usage
    // instead of an under-specified scenario the model has to guess its way through.
    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant with access to three tools: "calculate" for
            arithmetic, "getCurrentWeather" for real-world weather/temperature
            lookups, and "getCityInfo" for factual background about a place (what
            it's known for, population, history). Use a tool only when the question
            actually requires it - for anything else, answer directly from your own
            knowledge, don't call a tool just because one is available. When a
            question depends on the result of one tool to answer another part of
            the question, call the tools one at a time, in the correct order, using
            each result as needed for the next step. If a place name in the
            question looks misspelled, correct it to the most likely intended
            name before calling a tool with it. If a tool call still fails or
            can't find what was asked for, say so plainly in your answer instead
            of silently leaving that part out. Once you have everything you need,
            give a direct, concise final answer.
            """;

    @Autowired
    private OpenAiChatModel chatModel;

    @Test
    void modelCallsOnlyWeatherToolForWeatherOnlyQuestion() {
        List<String> calledTools = new ArrayList<>();
        String finalAnswer = runToCompletion("What's the weather in Paris?", calledTools);

        assertThat(calledTools).containsExactly("getCurrentWeather");
        assertThat(finalAnswer).isNotBlank();
    }

    @Test
    void modelCallsOnlyCalculatorToolForArithmeticOnlyQuestion() {
        List<String> calledTools = new ArrayList<>();
        String finalAnswer = runToCompletion("What is 9 times 6?", calledTools);

        assertThat(calledTools).containsExactly("calculate");
        assertThat(finalAnswer).contains("54");
    }

    @Test
    void modelChainsWeatherThenCalculatorForDependentQuestion() {
        List<String> calledTools = new ArrayList<>();
        String finalAnswer = runToCompletion(
                "What's the weather in Paris, and what's that temperature times 3?", calledTools);

        // Live temperature varies, so this checks the chaining mechanic (order,
        // both tools invoked) rather than a hardcoded result. AgentLoopServiceTest
        // covers the stronger numeric-consistency check on this same scenario.
        assertThat(calledTools).containsExactly("getCurrentWeather", "calculate");
        assertThat(finalAnswer).isNotBlank();
    }

    @Test
    void modelCallsOnlyCityInfoToolForFactualQuestion() {
        List<String> calledTools = new ArrayList<>();
        String finalAnswer = runToCompletion("What is Paris known for?", calledTools);

        assertThat(calledTools).containsExactly("getCityInfo");
        assertThat(finalAnswer).isNotBlank();
    }

    @Test
    void modelCallsBothWeatherAndCityInfoForCombinedQuestion() {
        List<String> calledTools = new ArrayList<>();
        String finalAnswer = runToCompletion(
                "Tell me a bit about Paris and its current weather.", calledTools);

        // These two are independent (neither needs the other's result), so the
        // model may call them in either order, or together in one turn - unlike
        // the weather->calculate case, order isn't the thing being verified here.
        // Not asserting an exact set: the model occasionally makes a reasonable
        // extra call (e.g. converting the temperature to Fahrenheit unprompted).
        assertThat(calledTools).contains("getCityInfo", "getCurrentWeather");
        assertThat(finalAnswer).isNotBlank();
    }

    /**
     * Drives the manual tool-calling loop to completion and records, in
     * order, the name of every tool the model chose to call.
     */
    private String runToCompletion(String question, List<String> calledToolsOut) {
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(
                        new CalculatorTool(),
                        new WeatherTool(RestClient.builder()),
                        new CityInfoTool(RestClient.builder())))
                .build();

        Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(question)), options);
        ChatResponse response = chatModel.call(prompt);

        while (response.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : response.getResult().getOutput().getToolCalls()) {
                calledToolsOut.add(toolCall.name());
            }
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(result.conversationHistory(), options);
            response = chatModel.call(prompt);
        }

        return response.getResult().getOutput().getText();
    }
}
