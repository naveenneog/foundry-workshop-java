package com.microsoft.foundry.workshop;

import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
import com.azure.ai.projects.models.CreateAgentOptions;
import com.azure.ai.projects.models.CreateRunOptions;
import com.azure.ai.projects.models.McpToolDefinition;
import com.azure.ai.projects.models.MessageRole;
import com.azure.ai.projects.models.RunStatus;
import com.azure.ai.projects.models.ThreadMessage;
import com.azure.ai.projects.models.ThreadRun;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.List;

/**
 * M5 · MCP Tools
 *
 * <p>Goal: connect an agent to a Model Context Protocol (MCP) server and let the
 * model call tools exposed over that protocol.
 *
 * <p>You'll use: {@link McpToolDefinition} to attach an MCP server endpoint
 * to an agent, then invoke it through the standard agent thread lifecycle.
 *
 * <p>MCP servers expose tools via a standardised JSON-RPC-over-SSE protocol.
 * Foundry routes the tool calls transparently — from your code's perspective the
 * invocation loop is identical to the function-calling loop in M3.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module05McpTools}
 */
public class Module05McpTools {

    /**
     * Replace with the URL of your own MCP server.
     *
     * <p>For local development you can use the reference "everything" MCP server:
     * {@code npx @modelcontextprotocol/server-everything} — which runs on
     * {@code http://localhost:3001/sse} by default.
     */
    private static final String MCP_SERVER_URL = System.getenv()
        .getOrDefault("MCP_SERVER_URL", "http://localhost:3001/sse");

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project    : " + config.projectEndpoint);
        System.out.println("Chat       : " + config.chatModel);
        System.out.println("MCP server : " + MCP_SERVER_URL);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Attach an MCP server as a tool ─────────────────────────────────
        McpToolDefinition mcpTool = new McpToolDefinition()
            .setServerLabel("workshop-mcp")
            .setServerUrl(MCP_SERVER_URL)
            .setRequireApproval("never");   // auto-approve for workshop demos

        System.out.println("MCP tool declared: " + mcpTool.getServerLabel());
        System.out.println();

        // ── 2. Create an agent with the MCP tool ───────────────────────────────
        Agent agent = projectClient.getAgentsClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("mcp-demo-agent")
                .setInstructions("You are a helpful assistant. Use the MCP tools available " +
                    "to you to answer questions. When a tool is relevant, call it.")
                .setTools(List.of(mcpTool))
        );

        System.out.println("Agent created: " + agent.getName());
        System.out.println();

        // ── 3. Invoke via standard thread / run lifecycle ──────────────────────
        System.out.println("=== MCP agent query ===");
        String reply = invokeAgent(projectClient, agent,
            "What is the current time in UTC? Use any available tool to find out.");
        System.out.println(reply);

        projectClient.getAgentsClient().deleteAgent(agent.getId());
    }

    private static String invokeAgent(
            AIProjectClient client, Agent agent, String userMessage)
            throws InterruptedException {

        AgentThread thread = client.getAgentsClient().createThread();
        client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, userMessage);

        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(agent.getId())
        );

        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED
            || run.getStatus() == RunStatus.REQUIRES_ACTION) {
            Thread.sleep(1_000);
            run = client.getAgentsClient().getRun(thread.getId(), run.getId());
            // MCP tool calls are handled server-side; no client-side dispatch needed.
        }

        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run did not complete: " + run.getStatus());
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
