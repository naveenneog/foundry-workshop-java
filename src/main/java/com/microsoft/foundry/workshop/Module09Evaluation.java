package com.microsoft.foundry.workshop;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M9 · Evaluation
 *
 * <p>Goal: measure answer quality systematically — score responses with quality
 * evaluators (relevance, groundedness, coherence) and a custom evaluator.
 *
 * <p>In Java, LLM-as-judge evaluation is implemented by sending scoring prompts
 * to an Azure OpenAI chat model. This approach mirrors the {@code azure-ai-evaluation}
 * SDK pattern used in the Python workshop.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module09Evaluation}
 */
public class Module09Evaluation {

    /** Small test dataset — row index 2 is deliberately wrong (1536 vs 3072). */
    public static final List<EvalRecord> TEST_DATA = List.of(
        new EvalRecord(
            "What does DefaultAzureCredential do in a Foundry app?",
            "DefaultAzureCredential tries credential sources in order (environment, " +
                "managed identity, az login) and uses the first that works — no secrets in code.",
            "It authenticates by trying several sources in sequence — environment " +
                "variables, managed identity, then your az login session — and uses the first " +
                "that succeeds, so you never hard-code secrets.",
            "Authenticates via a chain of sources (env, managed identity, az login); requires no secrets in code."
        ),
        new EvalRecord(
            "How does agent versioning work in Foundry?",
            "An agent is stored under a stable name. create_version stores a new version " +
                "whenever the definition changes; callers reference the name.",
            "Each agent has a stable name, and create_version stores a new numbered version " +
                "whenever the definition changes. Callers reference the agent by name.",
            "Agents are stored by name; create_version makes a new version on each change; callers reference by name."
        ),
        // Deliberately wrong — says 1536 but context says 3072
        new EvalRecord(
            "What embedding size does text-embedding-3-large return?",
            "text-embedding-3-large returns 3072-dimensional vectors.",
            "The text-embedding-3-large model returns 1536-dimensional vectors by default.",
            "text-embedding-3-large returns 3072-dimensional vectors."
        ),
        new EvalRecord(
            "What is the Responses API used for?",
            "The Responses API is the modern stateful surface that powers agents and tools; " +
                "a minimal call takes a model and an input and returns output_text.",
            "It's Foundry's modern, stateful interface that powers agents and tools. A minimal " +
                "call passes a model and an input, and the reply is in output_text.",
            "Modern stateful API that powers agents and tools; minimal call takes model + input, returns output_text."
        )
    );

    public static void main(String[] args) {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Judge   : " + config.chatModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        OpenAIClient judgeClient = new OpenAIClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        LlmJudge judge = new LlmJudge(judgeClient, config.chatModel);
        KeyTermCoverageEvaluator ktEval = new KeyTermCoverageEvaluator();

        // ── Spot-check individual rows ────────────────────────────────────────
        System.out.println("=== Spot checks ===");
        EvalRecord good = TEST_DATA.get(0);
        EvalRecord bad  = TEST_DATA.get(2);

        System.out.println("GOOD row");
        System.out.println("  relevance    : " + judge.relevance(good.query(), good.response()));
        System.out.println("  groundedness : " + judge.groundedness(good.query(), good.response(), good.context()));

        System.out.println("BAD row (wrong dimension)");
        System.out.println("  groundedness : " + judge.groundedness(bad.query(), bad.response(), bad.context()));
        System.out.println();

        // ── Batch evaluation ──────────────────────────────────────────────────
        System.out.println("=== Batch evaluation ===");
        double totalRelevance = 0, totalGroundedness = 0, totalCoherence = 0;
        double totalCoverage = 0;
        int n = TEST_DATA.size();

        for (EvalRecord record : TEST_DATA) {
            double relevance    = judge.relevance(record.query(), record.response());
            double groundedness = judge.groundedness(record.query(), record.response(), record.context());
            double coherence    = judge.coherence(record.query(), record.response());
            Map<String, Object> kt = ktEval.evaluate(record.response(), record.groundTruth());

            totalRelevance    += relevance;
            totalGroundedness += groundedness;
            totalCoherence    += coherence;
            totalCoverage     += (Double) kt.get("key_term_coverage");
        }

        System.out.println("Aggregate metrics:");
        System.out.printf("  %-32s %.2f%n", "relevance",        totalRelevance    / n);
        System.out.printf("  %-32s %.2f%n", "groundedness",     totalGroundedness / n);
        System.out.printf("  %-32s %.2f%n", "coherence",        totalCoherence    / n);
        System.out.printf("  %-32s %.2f%n", "key_term_coverage",totalCoverage     / n);
    }

    // ── EvalRecord ─────────────────────────────────────────────────────────────

    public record EvalRecord(String query, String context, String response, String groundTruth) {}

    // ── LLM-as-judge evaluator ─────────────────────────────────────────────────

    /**
     * Simple LLM-as-judge: sends a scoring prompt to the chat model and
     * parses the numeric score from the response.
     */
    public static class LlmJudge {

        private final OpenAIClient client;
        private final String model;

        public LlmJudge(OpenAIClient client, String model) {
            this.client = client;
            this.model  = model;
        }

        public double relevance(String query, String response) {
            String prompt = String.format(
                "Score the RELEVANCE of the answer to the question on a scale of 1–5.\n" +
                "Question: %s\nAnswer: %s\n" +
                "Return ONLY an integer between 1 and 5.", query, response);
            return scoreFromPrompt(prompt);
        }

        public double groundedness(String query, String response, String context) {
            String prompt = String.format(
                "Score the GROUNDEDNESS of the answer (how well it is supported by the context) " +
                "on a scale of 1–5.\n" +
                "Context: %s\nQuestion: %s\nAnswer: %s\n" +
                "Return ONLY an integer between 1 and 5.", context, query, response);
            return scoreFromPrompt(prompt);
        }

        public double coherence(String query, String response) {
            String prompt = String.format(
                "Score the COHERENCE (logical structure and clarity) of the answer on a scale of 1–5.\n" +
                "Question: %s\nAnswer: %s\n" +
                "Return ONLY an integer between 1 and 5.", query, response);
            return scoreFromPrompt(prompt);
        }

        private double scoreFromPrompt(String prompt) {
            try {
                ChatCompletions resp = client.getChatCompletions(model,
                    new ChatCompletionsOptions(List.of(
                        new ChatRequestSystemMessage("You are an evaluation assistant that returns only a number."),
                        new ChatRequestUserMessage(prompt)
                    ))
                );
                String text = resp.getChoices().get(0).getMessage().getContent().trim();
                return Double.parseDouble(text.replaceAll("[^0-9.]", "").trim());
            } catch (Exception e) {
                return 3.0; // neutral fallback on parse error
            }
        }
    }

    // ── Custom evaluator ──────────────────────────────────────────────────────

    /**
     * Deterministic evaluator: fraction of key terms from {@code groundTruth}
     * that appear in {@code response}.
     */
    public static class KeyTermCoverageEvaluator {

        private final int minLen;
        private final double threshold;

        public KeyTermCoverageEvaluator() {
            this(4, 0.8);
        }

        public KeyTermCoverageEvaluator(int minLen, double threshold) {
            this.minLen    = minLen;
            this.threshold = threshold;
        }

        public Map<String, Object> evaluate(String response, String groundTruth) {
            Set<String> terms = Arrays.stream(groundTruth.split("\\s+"))
                .map(w -> w.toLowerCase().replaceAll("[.,;:()]", ""))
                .filter(w -> w.length() >= minLen)
                .collect(Collectors.toSet());

            String lowerResponse = response.toLowerCase();
            long hits = terms.stream().filter(lowerResponse::contains).count();
            double coverage = terms.isEmpty() ? 0.0 : (double) hits / terms.size();
            coverage = Math.round(coverage * 100.0) / 100.0;

            Map<String, Object> result = new HashMap<>();
            result.put("key_term_coverage", coverage);
            result.put("key_term_pass", coverage >= threshold);
            return result;
        }
    }
}
