package com.microsoft.foundry.workshop;

import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.MessageTextContent;
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
        System.out.println("PersistentAgent   : " + AGENT_NAME);
        System.out.println();

        // ── 1. Build the PersistentAgentsClient ──────────────────────────────────────
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();

        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        System.out.println("projectClient : ready");
        System.out.println();

        // ── 2. Create an agent (v1) ────────────────────────────────────────────
        System.out.println("=== Create agent v1 ===");
        PersistentAgent agentV1 = createStorytellingAgent(projectClient, config.chatModel,
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
        PersistentAgent agentV2 = createStorytellingAgent(projectClient, config.chatModel,
            "You are a storytelling agent with a melancholic, noir voice. " +
            "You craft a single haunting sentence based on the user's prompt.");

        System.out.println("Name : " + agentV2.getName());
        System.out.println("Id   : " + agentV2.getId());
        System.out.println();

        String replyV2 = invokeAgent(projectClient, agentV2,
            "Tell me a one-line story about a lighthouse keeper.");
        System.out.println(replyV2);

        // Clean up — delete agents to avoid orphaned resources
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agentV1.getId());
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agentV2.getId());
    }

    /**
     * Create (or recreate) a storytelling agent with the given instructions.
     */
    public static PersistentAgent createStorytellingAgent(
            PersistentAgentsClient client, String chatModel, String instructions) {
        return client.getPersistentAgentsAdministrationClient().createAgent(
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
            PersistentAgentsClient client, PersistentAgent agent, String userMessage)
            throws InterruptedException {

        // Create a thread for this conversation turn
        PersistentAgentThread thread = client.getThreadsClient().createThread();

        // Post the user message
        client.getMessagesClient().createMessage(
            thread.getId(),
            MessageRole.USER,
            userMessage
        );

        // Start a run
        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), agent.getId()));

        // Poll until the run is no longer in-progress
        run = pollUntilDone(client, thread.getId(), run.getId());

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run did not complete successfully: " + run.getStatus());
        }

        // Retrieve the last assistant message
        List<ThreadMessage> messages = client.getMessagesClient().listMessages(thread.getId())
            .stream()
            .toList();

        for (ThreadMessage msg : messages) {
            if (msg.getRole() == MessageRole.AGENT) {
                return msg.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(c -> { MessageTextContent tc = (MessageTextContent) c; return tc.getText().getValue(); })
                    .findFirst()
                    .orElse("");
            }
        }
        return "";
    }

    private static ThreadRun pollUntilDone(
            PersistentAgentsClient client, String threadId, String runId)
            throws InterruptedException {
        ThreadRun run;
        do {
            Thread.sleep(1_000);
            run = client.getRunsClient().getRun(threadId, runId);
        } while (run.getStatus() == RunStatus.IN_PROGRESS
              || run.getStatus() == RunStatus.QUEUED);
        return run;
    }
}
