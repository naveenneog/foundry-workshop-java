package com.microsoft.foundry.workshop;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
import com.azure.ai.projects.models.AzureAISearchQueryType;
import com.azure.ai.projects.models.AzureAISearchToolDefinition;
import com.azure.ai.projects.models.AzureAISearchToolResource;
import com.azure.ai.projects.models.ConnectionType;
import com.azure.ai.projects.models.CreateAgentOptions;
import com.azure.ai.projects.models.CreateRunOptions;
import com.azure.ai.projects.models.GetConnectionOptions;
import com.azure.ai.projects.models.MessageRole;
import com.azure.ai.projects.models.RunStatus;
import com.azure.ai.projects.models.ThreadMessage;
import com.azure.ai.projects.models.ThreadRun;
import com.azure.ai.projects.models.ToolResources;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.HnswAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchProfile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * M4 · Grounding / RAG (Foundry IQ)
 *
 * <p>Goal: stop your agent hallucinating — ground its answers in your documents,
 * with citations.
 *
 * <p>You'll use: {@link SearchIndexClient} (vector + semantic index), a
 * {@link AzureAISearchToolDefinition} knowledge base, and an agent wired to it via
 * the Azure AI Projects client.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module04GroundingRagFoundryIq}
 *
 * <p>Prerequisites: set {@code SEARCH_ENDPOINT} in {@code .env} to your Azure AI
 * Search service URL.
 */
public class Module04GroundingRagFoundryIq {

    private static final String INDEX_NAME = "foundry-facts";

    /** Small inline corpus — replace with your own documents in production. */
    private static final List<Map<String, Object>> CORPUS = List.of(
        Map.of("id", "1", "title", "DefaultAzureCredential",
            "content", "DefaultAzureCredential tries credential sources in order " +
                "(environment, managed identity, az login) and uses the first that works. " +
                "No secrets in code.",
            "category", "security"),
        Map.of("id", "2", "title", "Agent versioning",
            "content", "An agent is stored under a stable name. create_version (Python) / " +
                "createAgent (Java) stores a new definition; callers reference the agent by name.",
            "category", "agents"),
        Map.of("id", "3", "title", "Embeddings",
            "content", "text-embedding-3-large returns 3072-dimensional vectors. " +
                "Use the embeddingModel deployment name when calling the embeddings endpoint.",
            "category", "models"),
        Map.of("id", "4", "title", "Responses API",
            "content", "The Responses API is the modern stateful surface that powers agents " +
                "and tools. A minimal call takes a model and an input and returns output_text.",
            "category", "api")
    );

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        if (config.searchEndpoint.isBlank()) {
            System.err.println("SEARCH_ENDPOINT is not set — please add it to .env");
            System.exit(1);
        }

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Search  : " + config.searchEndpoint);
        System.out.println("Index   : " + INDEX_NAME);
        System.out.println("Embed   : " + config.embeddingModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();

        OpenAIClient openAIClient = new OpenAIClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        SearchIndexClient indexClient = new SearchIndexClientBuilder()
            .endpoint(config.searchEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create the search index ─────────────────────────────────────────
        System.out.println("=== Creating search index ===");
        createSearchIndex(indexClient);

        // ── 2. Generate embeddings and upload documents ────────────────────────
        System.out.println("=== Uploading embedded documents ===");
        uploadDocuments(openAIClient, indexClient, config.embeddingModel);

        // ── 3. Wire an agent to the search index and ask a grounded question ──
        System.out.println("=== Grounded agent query ===");
        runGroundedAgent(projectClient, config.chatModel, config.searchEndpoint);
    }

    private static void createSearchIndex(SearchIndexClient indexClient) {
        SearchIndex index = new SearchIndex(INDEX_NAME)
            .setFields(Arrays.asList(
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("title", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("content", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("category", SearchFieldDataType.STRING).setFilterable(true),
                new SearchField("embedding", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                    .setSearchable(true)
                    .setVectorSearchDimensions(3072)
                    .setVectorSearchProfileName("hnsw-profile")
            ))
            .setVectorSearch(new VectorSearch()
                .setProfiles(List.of(
                    new VectorSearchProfile("hnsw-profile", "hnsw-config")
                ))
                .setAlgorithms(List.of(new HnswAlgorithmConfiguration("hnsw-config")))
            );

        indexClient.createOrUpdateIndex(index);
        System.out.println("Index '" + INDEX_NAME + "' created or updated.");
    }

    private static void uploadDocuments(
            OpenAIClient openAIClient,
            SearchIndexClient indexClient,
            String embeddingModel) {

        SearchClient searchClient = indexClient.getSearchClient(INDEX_NAME);

        List<String> texts = CORPUS.stream()
            .map(doc -> (String) doc.get("content"))
            .toList();

        Embeddings embeddings = openAIClient.getEmbeddings(
            embeddingModel, new EmbeddingsOptions(texts)
        );

        for (int i = 0; i < CORPUS.size(); i++) {
            Map<String, Object> doc = CORPUS.get(i);
            List<Double> vector = embeddings.getData().get(i).getEmbedding();
            // Add embedding to document
            Map<String, Object> indexDoc = new java.util.HashMap<>(doc);
            indexDoc.put("embedding", vector);
            searchClient.uploadDocuments(List.of(indexDoc));
        }

        System.out.println("Uploaded " + CORPUS.size() + " documents with embeddings.");
    }

    private static void runGroundedAgent(
            AIProjectClient projectClient, String chatModel, String searchEndpoint)
            throws InterruptedException {

        // Create an agent with Azure AI Search as a grounding tool
        AzureAISearchToolDefinition searchTool = new AzureAISearchToolDefinition();

        Agent agent = projectClient.getAgentsClient().createAgent(
            new CreateAgentOptions(chatModel)
                .setName("grounded-rag-agent")
                .setInstructions("You are a helpful assistant. Use the knowledge base " +
                    "to answer questions about Microsoft Foundry. Always cite your sources.")
                .setTools(List.of(searchTool))
                .setToolResources(new ToolResources()
                    .setAzureAISearch(new AzureAISearchToolResource()
                        .setIndexes(List.of(
                            new com.azure.ai.projects.models.AzureAISearchIndexResource()
                                .setIndexConnectionId(INDEX_NAME)
                                .setIndexName(INDEX_NAME)
                                .setQueryType(AzureAISearchQueryType.VECTOR_SEMANTIC_HYBRID)
                                .setTopK(3)
                        ))
                    )
                )
        );

        AgentThread thread = projectClient.getAgentsClient().createThread();
        projectClient.getAgentsClient().createMessage(thread.getId(), MessageRole.USER,
            "What embedding size does text-embedding-3-large return?");

        ThreadRun run = projectClient.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(agent.getId())
        );

        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(1_000);
            run = projectClient.getAgentsClient().getRun(thread.getId(), run.getId());
        }

        List<ThreadMessage> messages = projectClient.getAgentsClient()
            .listMessages(thread.getId()).stream().toList();
        for (ThreadMessage msg : messages) {
            if (msg.getRole() == MessageRole.ASSISTANT) {
                System.out.println(msg.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(c -> c.asText().getText().getValue())
                    .findFirst().orElse(""));
                break;
            }
        }

        projectClient.getAgentsClient().deleteAgent(agent.getId());
    }
}
