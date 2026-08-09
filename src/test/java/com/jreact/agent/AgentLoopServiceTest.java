package com.jreact.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class AgentLoopServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AgentLoopService agentLoopService;

    @Test
    void chainsWeatherThenCalculatorAndRecordsTheTrace() throws Exception {
        AgentResult result = agentLoopService.run(
                "What's the weather in Paris, and what's that temperature times 3?");

        assertThat(result.hitIterationLimit()).isFalse();
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).toolName()).isEqualTo("getCurrentWeather");
        assertThat(result.toolCalls().get(1).toolName()).isEqualTo("calculate");

        // Live temperature varies, so verify the chain is numerically correct
        // relative to whatever the real weather actually was, rather than a
        // hardcoded expected value.
        double reportedTemperature = objectMapper.readTree(result.toolCalls().get(0).result())
                .get("temperatureCelsius").asDouble();
        double calculatedResult = Double.parseDouble(result.toolCalls().get(1).result());

        assertThat(calculatedResult).isCloseTo(reportedTemperature * 3, within(0.05));
    }

    @Test
    void answersDirectlyWithoutToolsWhenNoneAreNeeded() {
        AgentResult result = agentLoopService.run("In one word, what is the capital of France?");

        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.hitIterationLimit()).isFalse();
        assertThat(result.answer()).isNotBlank();
    }

    @Test
    void toolExecutionErrorIsFedBackInsteadOfCrashingTheRequest() {
        AgentResult result = agentLoopService.run("What is 10 divided by 0? Use the calculate tool.");

        assertThat(result.answer()).isNotBlank();
        assertThat(result.hitIterationLimit()).isFalse();
        assertThat(result.toolCalls()).isNotEmpty();
    }

    @Test
    void handlesTwoCitiesEvenWithAMisspelledName() {
        // Regression test: "Muumbai" has no fuzzy match in Open-Meteo's
        // geocoding at all (confirmed directly against the API - it returns
        // zero results for the typo). The system prompt now tells the model to
        // correct obvious typos before calling the tool, so this should still
        // resolve both cities rather than silently dropping one.
        AgentResult result = agentLoopService.run(
                "How is the weather in Delhi and Muumbai, what is the difference between the two?");

        assertThat(result.hitIterationLimit()).isFalse();
        long weatherCalls = result.toolCalls().stream()
                .filter(call -> call.toolName().equals("getCurrentWeather"))
                .count();
        assertThat(weatherCalls).isEqualTo(2);
        assertThat(result.answer()).isNotBlank();
    }
}
