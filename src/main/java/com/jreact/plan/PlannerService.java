package com.jreact.plan;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

/**
 * Decomposes a request into an ordered Plan, upfront, before any tool
 * execution happens. Does not call tools itself - it only reasons about
 * what steps a ReAct loop would need to run later.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private static final String SYSTEM_PROMPT = """
            You are a planning assistant. Given a user's request, break it down
            into an ordered list of discrete steps needed to fully answer it.
            Each step should be a self-contained sub-goal that could be handled
            by an agent with access to three tools: "calculate" for arithmetic,
            "getCurrentWeather" for real-world weather/temperature lookups, and
            "getCityInfo" for factual background about a place. If a step needs
            no tool (e.g. a final comparison using earlier results), that's fine
            too - just describe the goal. Do NOT answer the user's request
            yourself, and do not call any tools - only produce the plan.
            Respond with ONLY the JSON object matching the schema you're given -
            do not repeat or include the schema itself in your response.
            """;

    private final OpenAiChatModel chatModel;
    private final BeanOutputConverter<PlannerOutput> converter = new BeanOutputConverter<>(PlannerOutput.class);

    public PlannerService(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public Plan plan(String request) {
        log.info("Planning request: \"{}\"", request);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(request + System.lineSeparator() + converter.getFormat()));

        ChatResponse response = chatModel.call(new Prompt(messages));
        String rawOutput = response.getResult().getOutput().getText();
        log.debug("Planner raw output: {}", rawOutput);

        PlannerOutput plannerOutput = converter.convert(extractLastJsonObject(rawOutput));
        List<PlanStep> steps = new ArrayList<>();
        for (PlannedStep plannedStep : plannerOutput.steps()) {
            steps.add(new PlanStep(plannedStep.order(), plannedStep.goal(), StepStatus.PENDING, null));
        }

        log.info("Planned {} step(s) for request", steps.size());
        for (PlanStep step : steps) {
            log.info("  {}. {}", step.order(), step.goal());
        }

        return new Plan(request, steps);
    }

    /**
     * gpt-4o-mini occasionally echoes the JSON Schema from the format
     * instructions back before the actual answer, leaving two JSON objects
     * concatenated in the response - which trips BeanOutputConverter's
     * strict single-value parsing. Scan for balanced top-level {...} blocks
     * (string-literal aware) and take the last one, since that's
     * consistently where the real answer lands.
     */
    private String extractLastJsonObject(String text) {
        int depth = 0;
        int start = -1;
        int lastStart = -1;
        int lastEnd = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    lastStart = start;
                    lastEnd = i + 1;
                }
            }
        }

        if (lastStart < 0) {
            throw new IllegalStateException("Planner response contained no JSON object: " + text);
        }
        return text.substring(lastStart, lastEnd);
    }
}
