package com.microsoft.foundry.workshop;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;

/**
 * Shared configuration loaded from a {@code .env} file (or real environment variables).
 *
 * <p>Every lab in the workshop calls {@link WorkshopConfig#load()} to read the same
 * variable names.  Copy {@code .env.example} to {@code .env} and fill in your values
 * before running the labs.
 */
public class WorkshopConfig {

    // ── Required for all labs ──────────────────────────────────────────────────
    public final String projectEndpoint;
    public final String chatModel;
    public final String embeddingModel;
    public final String azureSubscriptionId;

    // ── Optional — only needed by specific labs ────────────────────────────────
    public final String reasoningModel;
    public final String researchModel;
    public final String searchEndpoint;
    public final String appInsightsConnectionString;

    private WorkshopConfig(Dotenv env) {
        this.projectEndpoint          = require(env, "PROJECT_ENDPOINT");
        this.chatModel                = getOrDefault(env, "CHAT_MODEL", "gpt-4.1-mini");
        this.embeddingModel           = getOrDefault(env, "EMBEDDING_MODEL", "text-embedding-3-large");
        this.azureSubscriptionId      = getOrDefault(env, "AZURE_SUBSCRIPTION_ID", "");
        this.reasoningModel           = getOrDefault(env, "REASONING_MODEL", "o4-mini");
        this.researchModel            = getOrDefault(env, "RESEARCH_MODEL", "o3-deep-research");
        this.searchEndpoint           = getOrDefault(env, "SEARCH_ENDPOINT", "");
        this.appInsightsConnectionString = getOrDefault(env, "APP_INSIGHTS_CONN_STRING", "");
    }

    /**
     * Load configuration from the nearest {@code .env} file, then fall back to real
     * environment variables.
     */
    public static WorkshopConfig load() {
        Dotenv env;
        try {
            env = Dotenv.configure().ignoreIfMissing().load();
        } catch (DotenvException e) {
            env = Dotenv.configure().ignoreIfMissing().load();
        }
        return new WorkshopConfig(env);
    }

    /**
     * Derive the Azure OpenAI account endpoint from the project endpoint.
     *
     * <p>The account endpoint is the project endpoint with the
     * {@code /api/projects/<project>} suffix removed — used by evaluators and
     * fine-tuning labs that hit the account-level AOAI surface.
     */
    public String aoaiEndpoint() {
        int idx = projectEndpoint.indexOf("/api/projects/");
        if (idx < 0) {
            return projectEndpoint;
        }
        return projectEndpoint.substring(0, idx) + "/";
    }

    @Override
    public String toString() {
        return String.format(
            "WorkshopConfig{projectEndpoint='%s', chatModel='%s', embeddingModel='%s'}",
            projectEndpoint, chatModel, embeddingModel
        );
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String require(Dotenv env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required environment variable '" + key + "' is not set. " +
                "Copy .env.example to .env and fill in your values."
            );
        }
        return value;
    }

    private static String getOrDefault(Dotenv env, String key, String defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
