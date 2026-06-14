package com.microsoft.foundry.workshop;

import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
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
 * M2 · Your First Agent
 *
 * <p>Goal: turn a raw model into a named, versioned agent — give it instructions,
 * create it on Foundry, invoke it, then iterate safely.
 *
 * <p>You'll use: {@link AIProjectClient}, {@code agents.createAgent()},
 * thread-based invocation, and iterating on agent instructions.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module02YourFirstAgent}
 */
public class Module02YourFirstAgent {

    /** Stable, human-readable name. Re-running the lab creates a new version. */
    private static final String AGENT_NAME = "storytelling-agent";

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Chat    : " + config.chatModel);
        System.out.println("Agent   : " + AGENT_NAME);
        System.out.println();

        // ── 1. Build the AIProjectClient ──────────────────────────────────────
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();

        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        System.out.println("projectClient : ready");
        System.out.println();

        // ── 2. Create an agent (v1) ────────────────────────────────────────────
        System.out.println("=== Create agent v1 ===");
        Agent agentV1 = createStorytellingAgent(projectClient, config.chatModel,
            "You are a storytelling agent. " +
            "You craft engaging one-line stories based on user prompts and context.");

        System.out.println("Name    : " + agentV1.getName());
        System.out.println("Id      : " + agentV1.getId());
        System.out.println();

        // ── 3. Invoke the agent ────────────────────────────────────────────────
        System.out.println("=== Invoke agent v1 ===");
        String reply = invokeAgent(projectClient, agentV1,
            "Tell me a one-line story about a lighthouse keeper.");
        System.out.println(reply);
        System.out.println();

        // ── 4. Update (version) the agent instructions ────────────────────────
        System.out.println("=== Update agent (v2 — melancholic voice) ===");
        Agent agentV2 = createStorytellingAgent(projectClient, config.chatModel,
            "You are a storytelling agent with a melancholic, noir voice. " +
            "You craft a single haunting sentence based on the user's prompt.");

        System.out.println("Name : " + agentV2.getName());
        System.out.println("Id   : " + agentV2.getId());
        System.out.println();

        String replyV2 = invokeAgent(projectClient, agentV2,
            "Tell me a one-line story about a lighthouse keeper.");
        System.out.println(replyV2);

        // Clean up — delete agents to avoid orphaned resources
        projectClient.getAgentsClient().deleteAgent(agentV1.getId());
        projectClient.getAgentsClient().deleteAgent(agentV2.getId());
    }

    /**
     * Create (or recreate) a storytelling agent with the given instructions.
     */
    public static Agent createStorytellingAgent(
            AIProjectClient client, String chatModel, String instructions) {
        return client.getAgentsClient().createAgent(
            new CreateAgentOptions(chatModel)
                .setName(AGENT_NAME)
                .setInstructions(instructions)
        );
    }

    /**
     * Open a thread, post a user message, run the agent, wait for completion,
     * and return the assistant's reply text.
     */
    public static String invokeAgent(
            AIProjectClient client, Agent agent, String userMessage)
            throws InterruptedException {

        // Create a thread for this conversation turn
        AgentThread thread = client.getAgentsClient().createThread();

        // Post the user message
        client.getAgentsClient().createMessage(
            thread.getId(),
            MessageRole.USER,
            userMessage
        );

        // Start a run
        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(),
            new CreateRunOptions(agent.getId())
        );

        // Poll until the run is no longer in-progress
        run = pollUntilDone(client, thread.getId(), run.getId());

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run did not complete successfully: " + run.getStatus());
        }

        // Retrieve the last assistant message
        List<ThreadMessage> messages = client.getAgentsClient()
            .listMessages(thread.getId())
            .stream()
            .toList();

        for (ThreadMessage msg : messages) {
            if (msg.getRole() == MessageRole.ASSISTANT) {
                return msg.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(c -> c.asText().getText().getValue())
                    .findFirst()
                    .orElse("");
            }
        }
        return "";
    }

    private static ThreadRun pollUntilDone(
            AIProjectClient client, String threadId, String runId)
            throws InterruptedException {
        ThreadRun run;
        do {
            Thread.sleep(1_000);
            run = client.getAgentsClient().getRun(threadId, runId);
        } while (run.getStatus() == RunStatus.IN_PROGRESS
              || run.getStatus() == RunStatus.QUEUED);
        return run;
    }
}
