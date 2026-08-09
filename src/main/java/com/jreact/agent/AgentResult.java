package com.jreact.agent;

import java.util.List;

/**
 * Outcome of running the ReAct loop to completion (or to the iteration cap).
 */
public record AgentResult(
        String answer,
        List<ToolCallTrace> toolCalls,
        int iterationsUsed,
        boolean hitIterationLimit) {
}
