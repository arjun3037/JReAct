package com.jreact.tools;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real current-weather lookup via Open-Meteo — free, no API key required.
 * Two HTTP calls happen under the hood (geocode city -> lat/long, then
 * fetch current weather for those coordinates), but the LLM only ever sees
 * one tool: getCurrentWeather(city).
 */
@Component
public class WeatherTool {

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private final RestClient restClient;

    public WeatherTool(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Tool(description = "Get the current real-world weather for a city (via the free Open-Meteo API) — "
            + "returns temperature in Celsius and a short condition description. "
            + "Always use this tool when asked about weather or temperature instead of guessing.")
    public WeatherResult getCurrentWeather(@ToolParam(description = "City name, e.g. \"Paris\"") String city) {
        GeocodingResult location = geocode(city);
        return fetchCurrentWeather(location);
    }

    private GeocodingResult geocode(String city) {
        GeocodingResponse response = restClient.get()
                .uri(GEOCODING_URL + "?name={city}&count=1&language=en&format=json", city)
                .retrieve()
                .body(GeocodingResponse.class);

        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new IllegalArgumentException("Could not find a location named \"" + city + "\"");
        }
        return response.results().get(0);
    }

    private WeatherResult fetchCurrentWeather(GeocodingResult location) {
        ForecastResponse response = restClient.get()
                .uri(FORECAST_URL + "?latitude={lat}&longitude={lon}&current=temperature_2m,weather_code",
                        location.latitude(), location.longitude())
                .retrieve()
                .body(ForecastResponse.class);

        if (response == null || response.current() == null) {
            throw new IllegalStateException("Weather service returned no data for \"" + location.name() + "\"");
        }

        return new WeatherResult(
                location.name(),
                response.current().temperature2m(),
                describeWeatherCode(response.current().weatherCode()));
    }

    /** WMO weather interpretation codes, per Open-Meteo's documented subset. */
    private String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2, 3 -> "Partly cloudy";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 71, 73, 75 -> "Snow";
            case 80, 81, 82 -> "Rain showers";
            case 95, 96, 99 -> "Thunderstorm";
            default -> "Unknown (" + code + ")";
        };
    }

    public record WeatherResult(String city, double temperatureCelsius, String condition) {
    }

    private record GeocodingResponse(List<GeocodingResult> results) {
    }

    private record GeocodingResult(String name, double latitude, double longitude) {
    }

    private record ForecastResponse(CurrentWeather current) {
    }

    private record CurrentWeather(
            @JsonProperty("temperature_2m") double temperature2m,
            @JsonProperty("weather_code") int weatherCode) {
    }
}
