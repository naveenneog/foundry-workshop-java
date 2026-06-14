package com.microsoft.foundry.workshop;

import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
import com.azure.ai.projects.models.CreateAgentOptions;
import com.azure.ai.projects.models.CreateRunOptions;
import com.azure.ai.projects.models.FunctionDefinition;
import com.azure.ai.projects.models.FunctionToolDefinition;
import com.azure.ai.projects.models.MessageRole;
import com.azure.ai.projects.models.RequiredFunctionToolCall;
import com.azure.ai.projects.models.RunStatus;
import com.azure.ai.projects.models.SubmitToolOutputsAction;
import com.azure.ai.projects.models.ThreadMessage;
import com.azure.ai.projects.models.ThreadRun;
import com.azure.ai.projects.models.ToolOutput;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * M7 · Multi-Agent Orchestration
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
        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 1. Create specialist agents ────────────────────────────────────────
        Agent hrAgent = createSpecialist(projectClient, config.chatModel, "hr-specialist",
            "You are an HR specialist. Answer questions about hiring, benefits, and people policies. " +
            "Be concise and professional.");

        Agent marketingAgent = createSpecialist(projectClient, config.chatModel, "marketing-specialist",
            "You are a Marketing specialist. Answer questions about brand, campaigns, and growth. " +
            "Be creative and data-driven.");

        Agent productsAgent = createSpecialist(projectClient, config.chatModel, "products-specialist",
            "You are a Products specialist. Answer questions about features, roadmap, and pricing. " +
            "Be precise and customer-focused.");

        System.out.println("Specialists ready: hr, marketing, products");
        System.out.println();

        // ── 2. Create the router agent ─────────────────────────────────────────
        FunctionToolDefinition routeTool = buildRouteTool();
        Agent router = projectClient.getAgentsClient().createAgent(
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

            Agent specialist = switch (domain) {
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
        projectClient.getAgentsClient().deleteAgent(router.getId());
        projectClient.getAgentsClient().deleteAgent(hrAgent.getId());
        projectClient.getAgentsClient().deleteAgent(marketingAgent.getId());
        projectClient.getAgentsClient().deleteAgent(productsAgent.getId());
    }

    private static Agent createSpecialist(
            AIProjectClient client, String model, String name, String instructions) {
        return client.getAgentsClient().createAgent(
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
    private static String route(AIProjectClient client, Agent router, String question)
            throws Exception {
        AgentThread thread = client.getAgentsClient().createThread();
        client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, question);
        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(router.getId())
        );

        while (true) {
            Thread.sleep(1_000);
            run = client.getAgentsClient().getRun(thread.getId(), run.getId());

            if (run.getStatus() == RunStatus.REQUIRES_ACTION) {
                SubmitToolOutputsAction action = (SubmitToolOutputsAction) run.getRequiredAction();
                RequiredFunctionToolCall call = action.getSubmitToolOutputs().getToolCalls().get(0);
                JsonNode args = MAPPER.readTree(call.getFunction().getArguments());
                String domain = args.get("domain").asText();
                // Submit a placeholder output so the run can complete
                client.getAgentsClient().submitToolOutputsToRun(
                    thread.getId(), run.getId(),
                    List.of(new ToolOutput(call.getId(), "ok"))
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
    private static String ask(AIProjectClient client, Agent specialist, String question)
            throws InterruptedException {
        AgentThread thread = client.getAgentsClient().createThread();
        client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, question);
        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(specialist.getId())
        );

        while (run.getStatus() == RunStatus.IN_PROGRESS
            || run.getStatus() == RunStatus.QUEUED) {
            Thread.sleep(1_000);
            run = client.getAgentsClient().getRun(thread.getId(), run.getId());
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
