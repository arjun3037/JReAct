package com.jreact.plan;

/**
 * One step of a Plan. The planner only ever fills in order/goal (status
 * starts PENDING, result null) - status/result are populated as the
 * ExecutorService runs each step.
 */
public record PlanStep(int order, String goal, StepStatus status, String result) {
}
