package com.jreact.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CityInfoToolTest {

    private final CityInfoTool cityInfoTool = new CityInfoTool(RestClient.builder());

    @Test
    void returnsRealSummaryForKnownCity() {
        CityInfoTool.CityInfoResult result = cityInfoTool.getCityInfo("Paris");

        assertThat(result.title()).isEqualTo("Paris");
        assertThat(result.summary()).contains("France");
    }

    @Test
    void throwsForUnknownPlace() {
        assertThatThrownBy(() -> cityInfoTool.getCityInfo("Xyzzyxx-not-a-real-place-12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
