package com.jreact.plan;

/**
 * The planner's raw per-step output - just order and goal. Status/result
 * are execution-time fields the model has no business filling in, so they
 * live only on PlanStep, not here.
 */
public record PlannedStep(int order, String goal) {
}
