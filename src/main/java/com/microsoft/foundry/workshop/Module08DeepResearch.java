package com.microsoft.foundry.workshop;

import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
import com.azure.ai.projects.models.BingGroundingToolDefinition;
import com.azure.ai.projects.models.CreateAgentOptions;
import com.azure.ai.projects.models.CreateRunOptions;
import com.azure.ai.projects.models.MessageRole;
import com.azure.ai.projects.models.RunStatus;
import com.azure.ai.projects.models.ThreadMessage;
import com.azure.ai.projects.models.ThreadRun;
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
        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create the research agent with Bing grounding ──────────────────
        BingGroundingToolDefinition bingTool = new BingGroundingToolDefinition()
            .setConnectionId(BING_CONNECTION);

        Agent researchAgent = projectClient.getAgentsClient().createAgent(
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

        projectClient.getAgentsClient().deleteAgent(researchAgent.getId());
    }

    /**
     * Run the research agent with extended polling to allow for multi-step
     * web searches and synthesis.
     */
    public static String runResearch(
            AIProjectClient client, Agent agent, String question)
            throws InterruptedException {

        AgentThread thread = client.getAgentsClient().createThread();
        client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, question);

        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(agent.getId())
        );

        int elapsed = 0;
        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(2_000);
            elapsed += 2;
            if (elapsed % 10 == 0) {
                System.out.println("  Still researching... (" + elapsed + "s)");
            }
            run = client.getAgentsClient().getRun(thread.getId(), run.getId());
        }

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Research run did not complete: " + run.getStatus());
        }

        List<ThreadMessage> messages = client.getAgentsClient()
            .listMessages(thread.getId()).stream().toList();
        for (ThreadMessage msg : messages) {
            if (msg.getRole() == MessageRole.ASSISTANT) {
                return msg.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(c -> c.asText().getText().getValue())
                    .findFirst().orElse("");
            }
        }
        return "";
    }
}
