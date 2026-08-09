package com.jreact.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Real factual lookup via Wikipedia's REST summary API — free, no key.
 * The closest tool here to a genuine "search"/knowledge-retrieval capability,
 * as opposed to calculate/weather which are narrow single-purpose lookups.
 */
@Component
public class CityInfoTool {

    private static final String SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/{title}";

    private final RestClient restClient;

    public CityInfoTool(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Tool(description = "Get a short factual summary about a city (or any place) from Wikipedia — "
            + "use this when asked what a city is known for, its history, population, etc. "
            + "Not for weather or arithmetic, those have their own tools.")
    public CityInfoResult getCityInfo(@ToolParam(description = "City name, e.g. \"Paris\"") String city) {
        String title = city.trim().replace(' ', '_');
        try {
            WikipediaSummary summary = restClient.get()
                    .uri(SUMMARY_URL, title)
                    .retrieve()
                    .body(WikipediaSummary.class);

            if (summary == null || summary.extract() == null || summary.extract().isBlank()) {
                throw new IllegalArgumentException("No Wikipedia summary found for \"" + city + "\"");
            }
            return new CityInfoResult(summary.title(), summary.description(), summary.extract());
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("No Wikipedia page found for \"" + city + "\"");
        }
    }

    public record CityInfoResult(String title, String shortDescription, String summary) {
    }

    private record WikipediaSummary(String title, String description, String extract) {
    }
}
