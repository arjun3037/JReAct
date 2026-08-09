package com.jreact.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hits the real (free, keyless) Open-Meteo API, so these assert structure
 * and plausible ranges rather than exact values — live weather changes.
 */
class WeatherToolTest {

    private final WeatherTool weatherTool = new WeatherTool(RestClient.builder());

    @Test
    void returnsRealWeatherDataForKnownCity() {
        WeatherTool.WeatherResult result = weatherTool.getCurrentWeather("Paris");

        assertThat(result.city()).containsIgnoringCase("paris");
        assertThat(result.temperatureCelsius()).isBetween(-50.0, 55.0);
        assertThat(result.condition()).isNotBlank();
    }

    @Test
    void throwsForAnUnrecognizableCityInsteadOfSilentlyGuessing() {
        assertThatThrownBy(() -> weatherTool.getCurrentWeather("Xyzzyxx-not-a-real-place-12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
