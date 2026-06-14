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
 * M6 · PersistentAgent Memory
 *
 * <p>Goal: give an agent cross-turn context — let it remember what the user said
 * in earlier turns of the same conversation.
 *
 * <p>You'll use: a persistent {@link AgentThread} that accumulates message history
 * across multiple {@code createRun} calls within the same thread.
 *
 * <p>In Foundry the thread <em>is</em> the memory: messages are stored server-side
 * and replayed to the model on every new run. You never need to manage a conversation
 * history list manually — just keep reusing the same thread ID.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module06AgentMemory}
 */
public class Module06AgentMemory {

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Chat    : " + config.chatModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create a stateful agent ─────────────────────────────────────────
        PersistentAgent agent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("memory-agent")
                .setInstructions("You are a helpful assistant with a good memory. " +
                    "Remember what the user tells you across turns and reference " +
                    "it naturally in later replies.")
        );

        System.out.println("PersistentAgent created: " + agent.getName());
        System.out.println();

        // ── 2. Create one thread and reuse it for all turns ────────────────────
        PersistentAgentThread thread = projectClient.getThreadsClient().createThread();
        System.out.println("Thread id: " + thread.getId());
        System.out.println();

        // ── 3. Multi-turn conversation on the SAME thread ──────────────────────
        System.out.println("=== Turn 1 ===");
        String r1 = sendTurn(projectClient, thread, agent,
            "My name is Alex and I'm building an AI travel planner.");
        System.out.println(r1);
        System.out.println();

        System.out.println("=== Turn 2 ===");
        String r2 = sendTurn(projectClient, thread, agent,
            "What's my name and what am I building?");
        System.out.println(r2);
        System.out.println();

        System.out.println("=== Turn 3 ===");
        String r3 = sendTurn(projectClient, thread, agent,
            "What are three tips for building a great AI travel planner?");
        System.out.println(r3);
        System.out.println();

        // ── 4. Show the full message history stored in the thread ──────────────
        System.out.println("=== Full thread history ===");
        List<ThreadMessage> history = projectClient.getMessagesClient().listMessages(thread.getId()).stream().toList();
        System.out.println("Total messages in thread: " + history.size());
        history.forEach(msg -> {
            String text = msg.getContent().stream()
                .filter(c -> "text".equals(c.getType()))
                .map(c -> { MessageTextContent tc = (MessageTextContent) c; return tc.getText().getValue(); })
                .findFirst().orElse("");
            System.out.printf("[%s] %s%n", msg.getRole(), text);
        });

        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());
    }

    /**
     * Add a user message to the thread, run the agent, and return the reply.
     *
     * <p>Reusing the same thread is what provides memory — the model sees the full
     * message history on every run.
     */
    public static String sendTurn(
            PersistentAgentsClient client, PersistentAgentThread thread, PersistentAgent agent,
            String userMessage) throws InterruptedException {

        client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, userMessage);

        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), agent.getId()));

        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(1_000);
            run = client.getRunsClient().getRun(thread.getId(), run.getId());
        }

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed: " + run.getStatus());
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
