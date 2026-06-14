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

/**
 * M7 · Multi-PersistentAgent Orchestration
 *
 * <p>Goal: coordinate a team of specialist agents behind a router — one classifies
 * intent, three answer in their domain (HR, Marketing, Products).
 *
 * <p>In Java the orchestration is implemented via a router agent that uses a
 * {@link FunctionToolDefinition} to emit a routing decision, which your code then
 * dispatches to the correct specialist agent.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module07MultiAgentOrchestration}
 */
public class Module07MultiAgentOrchestration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Model   : " + config.chatModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create specialist agents ────────────────────────────────────────
        PersistentAgent hrAgent = createSpecialist(projectClient, config.chatModel, "hr-specialist",
            "You are an HR specialist. Answer questions about hiring, benefits, and people policies. " +
            "Be concise and professional.");

        PersistentAgent marketingAgent = createSpecialist(projectClient, config.chatModel, "marketing-specialist",
            "You are a Marketing specialist. Answer questions about brand, campaigns, and growth. " +
            "Be creative and data-driven.");

        PersistentAgent productsAgent = createSpecialist(projectClient, config.chatModel, "products-specialist",
            "You are a Products specialist. Answer questions about features, roadmap, and pricing. " +
            "Be precise and customer-focused.");

        System.out.println("Specialists ready: hr, marketing, products");
        System.out.println();

        // ── 2. Create the router agent ─────────────────────────────────────────
        FunctionToolDefinition routeTool = buildRouteTool();
        PersistentAgent router = projectClient.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("router-agent")
                .setInstructions("You are a router. Classify each user question into " +
                    "exactly one domain: 'hr', 'marketing', or 'products'. " +
                    "Call the route_to tool with your classification.")
                .setTools(List.of(routeTool))
        );
        System.out.println("Router agent ready");
        System.out.println();

        // ── 3. Route and answer sample questions ───────────────────────────────
        List<String> questions = List.of(
            "What is our parental leave policy?",
            "How can we improve our social media engagement?",
            "When will the new API versioning feature ship?"
        );

        for (String question : questions) {
            System.out.println("Q: " + question);
            String domain = route(projectClient, router, question);
            System.out.println("  → routed to: " + domain);

            PersistentAgent specialist = switch (domain) {
                case "hr" -> hrAgent;
                case "marketing" -> marketingAgent;
                case "products" -> productsAgent;
                default -> throw new IllegalStateException("Unknown domain: " + domain);
            };

            String answer = ask(projectClient, specialist, question);
            System.out.println("  A: " + answer);
            System.out.println();
        }

        // Clean up
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(router.getId());
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(hrAgent.getId());
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(marketingAgent.getId());
        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(productsAgent.getId());
    }

    private static PersistentAgent createSpecialist(
            PersistentAgentsClient client, String model, String name, String instructions) {
        return client.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(model).setName(name).setInstructions(instructions)
        );
    }

    private static FunctionToolDefinition buildRouteTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "domain": {
                  "type": "string",
                  "enum": ["hr", "marketing", "products"],
                  "description": "The domain to route the question to"
                }
              },
              "required": ["domain"]
            }
            """;
        return new FunctionToolDefinition(
            new FunctionDefinition("route_to", BinaryData.fromString(schema))
                .setDescription("Route the user question to the correct specialist domain.")
        );
    }

    /**
     * Ask the router to classify a question; return the domain string.
     */
    private static String route(PersistentAgentsClient client, PersistentAgent router, String question)
            throws Exception {
        PersistentAgentThread thread = client.getThreadsClient().createThread();
        client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, question);
        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), router.getId()));

        while (true) {
            Thread.sleep(1_000);
            run = client.getRunsClient().getRun(thread.getId(), run.getId());

            if (run.getStatus() == RunStatus.REQUIRES_ACTION) {
                SubmitToolOutputsAction action = (SubmitToolOutputsAction) run.getRequiredAction();
                RequiredFunctionToolCall call = (RequiredFunctionToolCall) action.getSubmitToolOutputs().getToolCalls().get(0);
                JsonNode args = MAPPER.readTree(call.getFunction().getArguments());
                String domain = args.get("domain").asText();
                // Submit a placeholder output so the run can complete
                client.getRunsClient().submitToolOutputsToRun(
                    thread.getId(), run.getId(),
                    List.of(new ToolOutput().setToolCallId(call.getId()).setOutput("ok"))
                );
                return domain;
            }
            if (run.getStatus() == RunStatus.COMPLETED
                || run.getStatus() == RunStatus.FAILED
                || run.getStatus() == RunStatus.CANCELLED) {
                return "products"; // fallback
            }
        }
    }

    /**
     * Ask a specialist a question and return its answer.
     */
    private static String ask(PersistentAgentsClient client, PersistentAgent specialist, String question)
            throws InterruptedException {
        PersistentAgentThread thread = client.getThreadsClient().createThread();
        client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, question);
        ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), specialist.getId()));

        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(1_000);
            run = client.getRunsClient().getRun(thread.getId(), run.getId());
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
