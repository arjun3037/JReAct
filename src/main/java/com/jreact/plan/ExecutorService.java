package com.jreact.plan;

import java.util.ArrayList;
import java.util.List;

import com.jreact.agent.AgentLoopService;
import com.jreact.agent.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

/**
 * Runs a Plan step by step, using the existing ReAct loop (AgentLoopService)
 * as the execution engine for each step - unmodified, just given a narrower
 * sub-goal each time. No re-planning: a failed step is recorded and
 * execution moves on to the next one.
 */
@Service
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are given the original request a user made, plus a series of
            plan steps that were executed to gather the information needed to
            answer it, along with each step's outcome. Using only that
            information, produce a direct, concise final answer to the
            original request. If some steps failed, answer as completely as
            you can from what succeeded and note plainly what's missing.
            """;

    private final AgentLoopService agentLoopService;
    private final OpenAiChatModel chatModel;

    public ExecutorService(AgentLoopService agentLoopService, OpenAiChatModel chatModel) {
        this.agentLoopService = agentLoopService;
        this.chatModel = chatModel;
    }

    public PlanAndExecuteResult execute(Plan plan) {
        log.info("Executing plan for \"{}\" ({} step(s))", plan.originalRequest(), plan.steps().size());

        List<PlanStep> completedSteps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            String subGoal = buildSubGoalPrompt(step, completedSteps);
            log.info("[step {}] {}", step.order(), step.goal());

            PlanStep completedStep;
            try {
                AgentResult result = agentLoopService.run(subGoal);
                completedStep = new PlanStep(step.order(), step.goal(), StepStatus.DONE, result.answer());
                log.info("[step {}] done -> {}", step.order(), result.answer());
            } catch (Exception e) {
                completedStep = new PlanStep(step.order(), step.goal(), StepStatus.FAILED, e.getMessage());
                log.warn("[step {}] failed -> {}", step.order(), e.getMessage());
            }
            completedSteps.add(completedStep);
        }

        String finalAnswer = synthesizeFinalAnswer(plan.originalRequest(), completedSteps);
        return new PlanAndExecuteResult(finalAnswer, new Plan(plan.originalRequest(), completedSteps));
    }

    private String buildSubGoalPrompt(PlanStep step, List<PlanStep> priorSteps) {
        if (priorSteps.isEmpty()) {
            return step.goal();
        }
        StringBuilder context = new StringBuilder("Context from previous steps:\n");
        for (PlanStep prior : priorSteps) {
            context.append("- ").append(prior.goal()).append(" -> [").append(prior.status()).append("] ")
                    .append(prior.result()).append('\n');
        }
        context.append("\nUsing the context above if relevant, complete this goal: ").append(step.goal());
        return context.toString();
    }

    private String synthesizeFinalAnswer(String originalRequest, List<PlanStep> completedSteps) {
        StringBuilder stepsSummary = new StringBuilder();
        for (PlanStep step : completedSteps) {
            stepsSummary.append(step.order()).append(". ").append(step.goal())
                    .append(" -> [").append(step.status()).append("] ").append(step.result()).append('\n');
        }

        String userPrompt = "Original request: " + originalRequest
                + "\n\nCompleted steps:\n" + stepsSummary;

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYNTHESIS_SYSTEM_PROMPT));
        messages.add(new UserMessage(userPrompt));

        ChatResponse response = chatModel.call(new Prompt(messages));
        return response.getResult().getOutput().getText();
    }
}
