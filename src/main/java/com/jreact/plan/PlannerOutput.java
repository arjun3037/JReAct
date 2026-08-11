package com.jreact.plan;

import java.util.List;

/**
 * Root type handed to BeanOutputConverter - wrapped in an object rather
 * than a bare List<PlannedStep>, since JSON Schema roots behave more
 * reliably as an object.
 */
public record PlannerOutput(List<PlannedStep> steps) {
}
