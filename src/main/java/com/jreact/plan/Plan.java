package com.jreact.plan;

import java.util.List;

public record Plan(String originalRequest, List<PlanStep> steps) {
}
