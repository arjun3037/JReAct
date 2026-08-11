package com.jreact.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ExecutorServiceTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private ExecutorService executorService;

    @Test
    void plansAndExecutesAMultiCityComparisonEndToEnd() {
        String request = "Find the weather in Paris and Tokyo, then tell me which is warmer and by how much";

        Plan plan = plannerService.plan(request);
        PlanAndExecuteResult result = executorService.execute(plan);

        assertThat(result.finalAnswer()).isNotBlank();
        assertThat(result.plan().steps()).hasSizeGreaterThanOrEqualTo(3);
        for (PlanStep step : result.plan().steps()) {
            assertThat(step.status()).isIn(StepStatus.DONE, StepStatus.FAILED);
            assertThat(step.result()).isNotNull();
        }
    }
}
