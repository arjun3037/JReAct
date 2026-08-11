package com.jreact.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlannerServiceTest {

    @Autowired
    private PlannerService plannerService;

    @Test
    void decomposesAMultiPartRequestIntoSequentialSteps() {
        Plan plan = plannerService.plan(
                "Find the weather in Paris and Tokyo, then tell me which is warmer and by how much");

        assertThat(plan.steps()).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            assertThat(step.order()).isEqualTo(i + 1);
            assertThat(step.goal()).isNotBlank();
            assertThat(step.status()).isEqualTo(StepStatus.PENDING);
            assertThat(step.result()).isNull();
        }
    }

    @Test
    void stillProducesAMinimalPlanForATrivialSingleFactRequest() {
        Plan plan = plannerService.plan("In one word, what is the capital of France?");

        assertThat(plan.steps()).isNotEmpty();
        assertThat(plan.steps().get(0).goal()).isNotBlank();
    }
}
