package com.jreact.agent;

import java.util.ArrayList;
import java.util.List;

import com.jreact.tools.CalculatorTool;
import com.jreact.tools.CityInfoTool;
import com.jreact.tools.WeatherTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The ReAct loop, driven explicitly step by step (Spring AI 2.0 no longer
 * loops for us — see CLAUDE.md). Each pass through the while loop is one
 * "iteration": call the model, and if it asked for tools, run them and feed
 * the results back for the next call. Stops either when the model returns a
 * final answer with no further tool calls, or when maxIterations is hit.
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

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

    private final OpenAiChatModel chatModel;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    private final OpenAiChatOptions toolOptions;
    private final int maxIterations;

    public AgentLoopService(
            OpenAiChatModel chatModel,
            WeatherTool weatherTool,
            CityInfoTool cityInfoTool,
            @Value("${jreact.agent.max-iterations:6}") int maxIterations) {
        this.chatModel = chatModel;
        this.maxIterations = maxIterations;
        this.toolOptions = OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(new CalculatorTool(), weatherTool, cityInfoTool))
                .build();
    }

    public AgentResult run(String question) {
        log.info("New question: \"{}\"", question);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(question));

        List<ToolCallTrace> trace = new ArrayList<>();
        Prompt prompt = new Prompt(messages, toolOptions);

        logPrompt(1, prompt);
        ChatResponse response = chatModel.call(prompt);

        int iteration = 1;
        while (response.hasToolCalls()) {
            if (iteration >= maxIterations) {
                log.warn("Iteration limit ({}) reached with the model still requesting tool calls — "
                        + "stopping and returning a partial result", maxIterations);
                return new AgentResult(
                        "I wasn't able to finish within the allowed number of reasoning steps ("
                                + maxIterations + "). Here's what I found so far: "
                                + summarizeTrace(trace),
                        trace, iteration, true);
            }

            List<AssistantMessage.ToolCall> requestedCalls = response.getResult().getOutput().getToolCalls();
            log.info("[iteration {}] model requested {} tool call(s)", iteration, requestedCalls.size());
            for (AssistantMessage.ToolCall call : requestedCalls) {
                log.info("  -> {}({})", call.name(), call.arguments());
            }

            ToolExecutionResult executionResult = toolCallingManager.executeToolCalls(prompt, response);
            recordTrace(requestedCalls, executionResult.conversationHistory(), trace);
            for (ToolCallTrace step : trace.subList(trace.size() - requestedCalls.size(), trace.size())) {
                log.info("  <- {} returned: {}", step.toolName(), step.result());
            }

            prompt = new Prompt(executionResult.conversationHistory(), toolOptions);
            iteration++;
            logPrompt(iteration, prompt);
            response = chatModel.call(prompt);
        }

        log.info("Finished after {} iteration(s), no further tool calls requested", iteration);
        return new AgentResult(response.getResult().getOutput().getText(), trace, iteration, false);
    }

    /**
     * Dumps the exact message list about to be sent to the model — the real
     * "prompt" (system + user + prior tool calls/results), not just the
     * question. DEBUG-only since this repeats the growing history every
     * iteration and gets noisy; flip logging.level.com.jreact to DEBUG in
     * application.yml to see it.
     */
    private void logPrompt(int iteration, Prompt prompt) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[iteration {}] prompt being sent to the model ({} message(s)):",
                iteration, prompt.getInstructions().size());
        for (Message message : prompt.getInstructions()) {
            log.debug("  [{}] {}", message.getMessageType(), renderForLog(message));
        }
    }

    private String renderForLog(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            StringBuilder rendered = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                rendered.append(response.name()).append(" -> ").append(response.responseData()).append("  ");
            }
            return rendered.toString();
        }
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            StringBuilder rendered = new StringBuilder("requests: ");
            for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                rendered.append(call.name()).append('(').append(call.arguments()).append(") ");
            }
            return rendered.toString();
        }
        return message.getText();
    }

    private void recordTrace(
            List<AssistantMessage.ToolCall> requestedCalls,
            List<Message> conversationHistory,
            List<ToolCallTrace> traceOut) {
        for (AssistantMessage.ToolCall call : requestedCalls) {
            String result = findToolResult(conversationHistory, call.id());
            traceOut.add(new ToolCallTrace(call.name(), call.arguments(), result));
        }
    }

    private String findToolResult(List<Message> conversationHistory, String toolCallId) {
        for (Message message : conversationHistory) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                    if (toolResponse.id().equals(toolCallId)) {
                        return toolResponse.responseData();
                    }
                }
            }
        }
        return null;
    }

    private String summarizeTrace(List<ToolCallTrace> trace) {
        if (trace.isEmpty()) {
            return "no tool results yet.";
        }
        StringBuilder summary = new StringBuilder();
        for (ToolCallTrace step : trace) {
            summary.append(step.toolName()).append("(").append(step.arguments()).append(") -> ")
                    .append(step.result()).append("; ");
        }
        return summary.toString();
    }
}
