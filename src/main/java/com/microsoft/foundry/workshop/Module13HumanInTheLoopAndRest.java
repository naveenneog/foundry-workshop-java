package com.microsoft.foundry.workshop;

import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.FunctionDefinition;
import com.azure.ai.agents.persistent.models.FunctionToolDefinition;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.RequiredFunctionToolCall;
import com.azure.ai.agents.persistent.models.RequiredToolCall;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.SubmitToolOutputsAction;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.ToolOutput;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * M13 · Human-in-the-Loop &amp; REST
 *
 * <p>Goal: add human approval gates to agent tool calls, and show how to invoke
 * Foundry agents via raw REST for scenarios where the SDK is not available.
 *
 * <p>This module demonstrates:
 * <ol>
 *   <li><b>Human-in-the-loop:</b> intercept sensitive tool calls and require
 *       explicit human approval before executing them.</li>
 *   <li><b>Direct REST invocation:</b> call the Foundry agent REST endpoint
 *       using {@link com.azure.core.http.HttpClient} with a bearer token.</li>
 * </ol>
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module13HumanInTheLoopAndRest}
 */
public class Module13HumanInTheLoopAndRest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tool calls matching this pattern require human approval. */
    private static final List<String> HIGH_RISK_TOOLS = List.of(
        "send_email", "delete_record", "transfer_funds", "deploy_code"
    );

    public static void main(String[] args) throws Exception {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Chat    : " + config.chatModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Define a "send_email" tool that requires approval ───────────────
        FunctionToolDefinition sendEmailTool = new FunctionToolDefinition(
            new FunctionDefinition("send_email", BinaryData.fromString("""
                {
                  "type": "object",
                  "properties": {
                    "to": {"type": "string", "description": "Recipient email address"},
                    "subject": {"type": "string", "description": "Email subject"},
                    "body": {"type": "string", "description": "Email body"}
                  },
                  "required": ["to", "subject", "body"]
                }
                """))
                .setDescription("Send an email to a recipient. Requires human approval.")
        );

        FunctionToolDefinition getInfoTool = new FunctionToolDefinition(
            new FunctionDefinition("get_info", BinaryData.fromString("""
                {
                  "type": "object",
                  "properties": {
                    "topic": {"type": "string", "description": "Topic to get information about"}
                  },
                  "required": ["topic"]
                }
                """))
                .setDescription("Get information about a topic. Does NOT require approval.")
        );

        // ── 2. Create the agent ───────────────────────────────────────────────
        PersistentAgent agent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("hitl-agent")
                .setInstructions("You are a helpful assistant. Use tools when appropriate. " +
                    "For send_email, always call the tool (do not refuse).")
                .setTools(List.of(sendEmailTool, getInfoTool))
        );

        System.out.println("PersistentAgent created: " + agent.getName());
        System.out.println();

        // ── 3. Run with human-in-the-loop approval ────────────────────────────
        System.out.println("=== Human-in-the-loop demo ===");
        String userRequest = "Please get info about Azure AI Foundry, then send a summary " +
            "to team@example.com with subject 'Foundry Summary'.";

        String reply = runWithApproval(projectClient, agent, userRequest);
        System.out.println("Final response: " + reply);

        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());
    }

    /**
     * Run an agent with a human approval gate for high-risk tool calls.
     *
     * <p>When a pending tool call matches {@link #HIGH_RISK_TOOLS}, pause and ask
     * the operator to approve or reject it before continuing.
     */
    public static String runWithApproval(
            PersistentAgentsClient client, PersistentAgent agent, String userMessage)
            throws Exception {

        PersistentAgentThread thread = client.getThreadsClient().createThread();
        client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, userMessage);

        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), agent.getId()));

        while (true) {
            Thread.sleep(1_000);
            run = client.getRunsClient().getRun(thread.getId(), run.getId());

            if (run.getStatus() == RunStatus.REQUIRES_ACTION) {
                SubmitToolOutputsAction action = (SubmitToolOutputsAction) run.getRequiredAction();
                List<ToolOutput> toolOutputs = new ArrayList<>();

                for (RequiredToolCall _toolCall : action.getSubmitToolOutputs().getToolCalls()) {
                    RequiredFunctionToolCall call = (RequiredFunctionToolCall) _toolCall;
                    String toolName = call.getFunction().getName();
                    String argsJson = call.getFunction().getArguments();
                    String output;

                    if (HIGH_RISK_TOOLS.contains(toolName)) {
                        output = approvalGate(toolName, argsJson);
                    } else {
                        output = dispatchTool(toolName, argsJson);
                    }
                    toolOutputs.add(new ToolOutput().setToolCallId(call.getId()).setOutput(output));
                }

                run = client.getRunsClient().submitToolOutputsToRun(
                    thread.getId(), run.getId(), toolOutputs
                );

            } else if (run.getStatus() == RunStatus.COMPLETED) {
                break;
            } else if (run.getStatus() != RunStatus.IN_PROGRESS
                    && run.getStatus() != RunStatus.QUEUED) {
                throw new RuntimeException("Run failed: " + run.getStatus());
            }
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

    /**
     * Pause and ask the operator to approve or reject a high-risk tool call.
     *
     * <p>In production this could be a webhook, a Slack message, or a database row
     * that a human reviews in a UI. Here we prompt stdin for simplicity.
     */
    private static String approvalGate(String toolName, String argsJson) throws Exception {
        JsonNode args = MAPPER.readTree(argsJson);
        System.out.println();
        System.out.println("⚠️  APPROVAL REQUIRED ⚠️");
        System.out.println("Tool  : " + toolName);
        System.out.println("Args  : " + args.toPrettyString());
        System.out.print("Approve? [y/N] ");
        System.out.flush();

        String input = new Scanner(System.in).nextLine().trim();
        if ("y".equalsIgnoreCase(input) || "yes".equalsIgnoreCase(input)) {
            System.out.println("Approved — executing " + toolName);
            return dispatchTool(toolName, argsJson);
        } else {
            System.out.println("Rejected by operator.");
            return "REJECTED: The operator did not approve this action.";
        }
    }

    /** Mock tool dispatcher. */
    private static String dispatchTool(String toolName, String argsJson) throws Exception {
        JsonNode args = MAPPER.readTree(argsJson);
        return switch (toolName) {
            case "send_email" -> String.format(
                "Email sent to %s: subject='%s'",
                args.get("to").asText(), args.get("subject").asText());
            case "get_info" -> String.format(
                "Information about '%s': Azure AI Foundry is Microsoft's unified AI platform.",
                args.get("topic").asText());
            default -> "Tool '" + toolName + "' executed successfully.";
        };
    }
}
