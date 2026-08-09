package com.jreact.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forces the cap down to 1 so a question that needs at least one tool call
 * can never finish — proves the loop stops gracefully instead of looping
 * forever or crashing when the limit is hit.
 */
@SpringBootTest
@TestPropertySource(properties = "jreact.agent.max-iterations=1")
class AgentLoopIterationCapTest {

    @Autowired
    private AgentLoopService agentLoopService;

    @Test
    void stopsGracefullyWhenIterationLimitIsHit() {
        AgentResult result = agentLoopService.run("What's the weather in Paris?");

        assertThat(result.hitIterationLimit()).isTrue();
        assertThat(result.answer()).isNotBlank();
    }
}
