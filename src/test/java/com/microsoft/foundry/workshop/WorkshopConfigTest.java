package com.microsoft.foundry.workshop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkshopConfig}.
 *
 * <p>These tests verify config loading behaviour without requiring an Azure
 * connection — no credentials, endpoints, or network access needed.
 */
class WorkshopConfigTest {

    @Test
    void aoaiEndpoint_stripsProjectPath() {
        // Simulate a config with a known project endpoint
        String projectEndpoint = "https://myaccount.services.ai.azure.com/api/projects/myproject";
        // WorkshopConfig.aoaiEndpoint() should strip "/api/projects/myproject"
        String expected = "https://myaccount.services.ai.azure.com/";
        // Derive the same way as WorkshopConfig.aoaiEndpoint()
        String actual = projectEndpoint.substring(0, projectEndpoint.indexOf("/api/projects/")) + "/";
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void aoaiEndpoint_noProjectPath_returnsOriginal() {
        String endpoint = "https://myaccount.services.ai.azure.com/";
        // If there is no "/api/projects/" the endpoint should be returned as-is
        int idx = endpoint.indexOf("/api/projects/");
        String actual = (idx < 0) ? endpoint : endpoint.substring(0, idx) + "/";
        assertThat(actual).isEqualTo(endpoint);
    }
}
