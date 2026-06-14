package com.microsoft.foundry.workshop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link Module09Evaluation.KeyTermCoverageEvaluator}.
 *
 * <p>Fully offline — no Azure calls or credentials needed.
 */
class Module09EvaluationTest {

    private final Module09Evaluation.KeyTermCoverageEvaluator evaluator =
        new Module09Evaluation.KeyTermCoverageEvaluator();

    @Test
    void perfectCoverage_returnsOne() {
        String groundTruth = "Authenticates with managed identity and az login";
        // Response contains all key terms (length >= 4)
        String response = "Authenticates with managed identity and az login session";
        Map<String, Object> result = evaluator.evaluate(response, groundTruth);
        assertThat((Double) result.get("key_term_coverage")).isGreaterThanOrEqualTo(0.9);
        assertThat((Boolean) result.get("key_term_pass")).isTrue();
    }

    @Test
    void zeroCoverage_returnsFalse() {
        String groundTruth = "text-embedding-3-large returns 3072-dimensional vectors";
        // Deliberately wrong response — says 1536
        String response = "The model returns 1536-dimensional vectors by default";
        Map<String, Object> result = evaluator.evaluate(response, groundTruth);
        assertThat((Boolean) result.get("key_term_pass")).isFalse();
    }

    @Test
    void emptyGroundTruth_returnsZeroCoverage() {
        Map<String, Object> result = evaluator.evaluate("some response", "");
        assertThat((Double) result.get("key_term_coverage")).isEqualTo(0.0);
    }

    @Test
    void customThreshold_stricterCheck() {
        Module09Evaluation.KeyTermCoverageEvaluator strict =
            new Module09Evaluation.KeyTermCoverageEvaluator(4, 0.9);
        String groundTruth = "agents tools memory versioning embeddings security";
        String partialResponse = "agents and tools"; // covers 2 of 5 key terms
        Map<String, Object> result = strict.evaluate(partialResponse, groundTruth);
        assertThat((Boolean) result.get("key_term_pass")).isFalse();
    }

    @Test
    void testDataRow3IsDeliberatelyWrong() {
        // Row index 2 in Module09Evaluation.TEST_DATA says 1536; context says 3072
        Module09Evaluation.EvalRecord bad = Module09Evaluation.TEST_DATA.get(2);
        assertThat(bad.response()).contains("1536");
        assertThat(bad.groundTruth()).contains("3072");

        Map<String, Object> result = evaluator.evaluate(bad.response(), bad.groundTruth());
        // The wrong answer should fail key-term coverage
        assertThat((Boolean) result.get("key_term_pass")).isFalse();
    }
}
