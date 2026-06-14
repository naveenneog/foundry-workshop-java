package com.microsoft.foundry.workshop;

import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.BingGroundingSearchConfiguration;
import com.azure.ai.agents.persistent.models.BingGroundingSearchToolParameters;
import com.azure.ai.agents.persistent.models.BingGroundingToolDefinition;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.List;

/**
 * M8 · Deep Research
 *
 * <p>Goal: run agentic research loops with cited synthesis — an agent that
 * searches the web iteratively, synthesises what it finds, and cites its sources.
 *
 * <p>You'll use: a reasoning model ({@code o3-deep-research} or equivalent) wired
 * to {@link BingGroundingToolDefinition} (or your preferred web-search connection)
 * so it can search and synthesise iteratively.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module08DeepResearch}
 *
 * <p>Prerequisites: set {@code RESEARCH_MODEL} in {@code .env}
 * (default: {@code o3-deep-research}).
 */
public class Module08DeepResearch {

    /** Connection name for your Bing Grounding resource in the Foundry project. */
    private static final String BING_CONNECTION = System.getenv()
        .getOrDefault("BING_CONNECTION", "bing-grounding");

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project        : " + config.projectEndpoint);
        System.out.println("Research model : " + config.researchModel);
        System.out.println("Bing connection: " + BING_CONNECTION);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create the research agent with Bing grounding ──────────────────
        BingGroundingToolDefinition bingTool = new BingGroundingToolDefinition(
            new BingGroundingSearchToolParameters(
                List.of(new BingGroundingSearchConfiguration(BING_CONNECTION))
            )
        );

        PersistentAgent researchAgent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(config.researchModel)
                .setName("deep-research-agent")
                .setInstructions(
                    "You are a thorough research assistant. When asked a question:\n" +
                    "1. Search the web for relevant, up-to-date information.\n" +
                    "2. Synthesise findings across multiple sources.\n" +
                    "3. Cite every factual claim with its source URL.\n" +
                    "4. Summarise key findings in a structured format."
                )
                .setTools(List.of(bingTool))
        );

        System.out.println("Research agent created: " + researchAgent.getName());
        System.out.println();

        // ── 2. Run a deep research query ──────────────────────────────────────
        String researchQuestion =
            "What are the latest developments in AI agent frameworks in 2024–2025? " +
            "Focus on Microsoft's approach vs. other major players. " +
            "Provide a structured summary with citations.";

        System.out.println("Research question: " + researchQuestion);
        System.out.println();
        System.out.println("Running deep research (this may take 30–120 seconds)...");
        System.out.println();

        String report = runResearch(projectClient, researchAgent, researchQuestion);
        System.out.println(report);

        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(researchAgent.getId());
    }

    /**
     * Run the research agent with extended polling to allow for multi-step
     * web searches and synthesis.
     */
    public static String runResearch(
            PersistentAgentsClient client, PersistentAgent agent, String question)
            throws InterruptedException {

        PersistentAgentThread thread = client.getThreadsClient().createThread();
        client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, question);

        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), agent.getId()));

        int elapsed = 0;
        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(2_000);
            elapsed += 2;
            if (elapsed % 10 == 0) {
                System.out.println("  Still researching... (" + elapsed + "s)");
            }
            run = client.getRunsClient().getRun(thread.getId(), run.getId());
        }

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Research run did not complete: " + run.getStatus());
        }

        List<ThreadMessage> messages = client.getMessagesClient().listMessages(thread.getId()).stream().toList();
        for (ThreadMessage msg : messages) {
            if (msg.getRole() == MessageRole.AGENT) {
                return msg.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(c -> { MessageTextContent tc = (MessageTextContent) c; return tc.getText().getValue(); })
                    .findFirst().orElse("");
            }
        }
        return "";
    }
}
