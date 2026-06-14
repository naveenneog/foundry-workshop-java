package com.microsoft.foundry.workshop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the mock {@code get_weather} implementation in
 * {@link Module03ToolsAndFunctionCalling}.
 */
class Module03ToolsAndFunctionCallingTest {

    @Test
    void getWeather_knownCityCelsius_returnsCorrectTemp() {
        String result = Module03ToolsAndFunctionCalling.getWeather("Zurich", "celsius");
        assertThat(result).contains("Zurich");
        assertThat(result).contains("18°C");
    }

    @Test
    void getWeather_knownCityFahrenheit_convertsCorrectly() {
        // 18°C = 64°F
        String result = Module03ToolsAndFunctionCalling.getWeather("Zurich", "fahrenheit");
        assertThat(result).contains("Zurich");
        assertThat(result).contains("°F");
    }

    @Test
    void getWeather_unknownCity_usesDefaultTemp() {
        String result = Module03ToolsAndFunctionCalling.getWeather("Atlantis", "celsius");
        assertThat(result).contains("Atlantis");
        assertThat(result).contains("21°C");
    }

    @Test
    void getWeather_oslo_returnsExpected() {
        String result = Module03ToolsAndFunctionCalling.getWeather("Oslo", "celsius");
        assertThat(result).contains("Oslo");
        assertThat(result).contains("7°C");
    }

    @Test
    void getWeather_cairo_returnsExpected() {
        String result = Module03ToolsAndFunctionCalling.getWeather("Cairo", "celsius");
        assertThat(result).contains("Cairo");
        assertThat(result).contains("34°C");
    }

    @Test
    void buildGetWeatherTool_hasCorrectName() {
        var tool = Module03ToolsAndFunctionCalling.buildGetWeatherTool();
        assertThat(tool.getFunction().getName()).isEqualTo("get_weather");
    }

    @Test
    void buildGetWeatherTool_hasDescription() {
        var tool = Module03ToolsAndFunctionCalling.buildGetWeatherTool();
        assertThat(tool.getFunction().getDescription()).isNotBlank();
    }
}
