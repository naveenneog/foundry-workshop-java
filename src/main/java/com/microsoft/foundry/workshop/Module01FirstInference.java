package com.microsoft.foundry.workshop;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.IterableStream;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.Arrays;
import java.util.List;

/**
 * M1 · First Inference
 *
 * <p>Goal: make your first model calls on Foundry — chat, embeddings, streaming,
 * and a simple Responses-style call — all from one Azure OpenAI client.
 *
 * <p>You'll use: {@link OpenAIClient}, {@code chat.completions}, {@code embeddings},
 * and streaming via {@link IterableStream}.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module01FirstInference}
 */
public class Module01FirstInference {

    public static void main(String[] args) {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Chat    : " + config.chatModel);
        System.out.println("Embed   : " + config.embeddingModel);
        System.out.println();

        // ── 1. Build the Azure OpenAI client ──────────────────────────────────
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();

        OpenAIClient openAIClient = new OpenAIClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        System.out.println("openAIClient : ready");
        System.out.println();

        // ── 2. Chat completions ───────────────────────────────────────────────
        System.out.println("=== Chat Completions ===");
        chatCompletions(openAIClient, config.chatModel);
        System.out.println();

        // ── 3. Embeddings ─────────────────────────────────────────────────────
        System.out.println("=== Embeddings ===");
        embeddings(openAIClient, config.embeddingModel);
        System.out.println();

        // ── 4. Streaming ──────────────────────────────────────────────────────
        System.out.println("=== Streaming ===");
        streamingChat(openAIClient, config.chatModel);
        System.out.println();
    }

    /**
     * Classic chat surface: pass the deployment name and a list of messages.
     */
    public static ChatCompletions chatCompletions(OpenAIClient client, String chatModel) {
        List<ChatRequestMessage> messages = Arrays.asList(
            new ChatRequestSystemMessage("You are a concise technical assistant."),
            new ChatRequestUserMessage("What is catastrophic forgetting in neural networks?")
        );

        ChatCompletions response = client.getChatCompletions(
            chatModel,
            new ChatCompletionsOptions(messages)
        );

        System.out.println("Model  : " + response.getModel());
        System.out.println("Tokens : " + response.getUsage().getTotalTokens());
        System.out.println();
        System.out.println(response.getChoices().get(0).getMessage().getContent());

        return response;
    }

    /**
     * Turn text into vectors — the foundation for retrieval (used heavily in M4).
     */
    public static Embeddings embeddings(OpenAIClient client, String embeddingModel) {
        List<String> texts = Arrays.asList(
            "Microsoft Foundry centralises model governance behind one platform.",
            "Embeddings turn text into vectors for semantic search.",
            "Each project authenticates with DefaultAzureCredential."
        );

        EmbeddingsOptions options = new EmbeddingsOptions(texts);
        Embeddings result = client.getEmbeddings(embeddingModel, options);

        System.out.println("Model      : " + embeddingModel);
        System.out.println("Dimensions : " + result.getData().get(0).getEmbedding().size());
        for (int i = 0; i < result.getData().size(); i++) {
            List<Float> v = result.getData().get(i).getEmbedding();
            System.out.printf("[%d] [%.4f, %.4f, %.4f, ...]  (%d dims)%n",
                i, v.get(0), v.get(1), v.get(2), v.size());
        }

        return result;
    }

    /**
     * Stream tokens as they are generated for a responsive UI experience.
     */
    public static void streamingChat(OpenAIClient client, String chatModel) {
        List<ChatRequestMessage> messages = List.of(
            new ChatRequestUserMessage("In one sentence, what is Microsoft Foundry?")
        );

        IterableStream<ChatCompletions> stream =
            client.getChatCompletionsStream(
                chatModel,
                new ChatCompletionsOptions(messages)
            );

        stream.forEach(chunk -> {
            if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                String delta = chunk.getChoices().get(0).getDelta().getContent();
                if (delta != null) {
                    System.out.print(delta);
                    System.out.flush();
                }
            }
        });
        System.out.println();
    }
}
