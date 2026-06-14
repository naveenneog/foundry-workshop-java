package com.microsoft.foundry.workshop;

import com.azure.ai.projects.AIProjectClient;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.models.Agent;
import com.azure.ai.projects.models.AgentThread;
import com.azure.ai.projects.models.CreateAgentOptions;
import com.azure.ai.projects.models.CreateRunOptions;
import com.azure.ai.projects.models.FunctionToolDefinition;
import com.azure.ai.projects.models.FunctionDefinition;
import com.azure.ai.projects.models.MessageRole;
import com.azure.ai.projects.models.RequiredAction;
import com.azure.ai.projects.models.RequiredFunctionToolCall;
import com.azure.ai.projects.models.RunStatus;
import com.azure.ai.projects.models.SubmitToolOutputsAction;
import com.azure.ai.projects.models.ThreadMessage;
import com.azure.ai.projects.models.ThreadRun;
import com.azure.ai.projects.models.ToolDefinition;
import com.azure.ai.projects.models.ToolOutput;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M3 · Tools & Function Calling
 *
 * <p>Goal: give an agent tools — a custom function that the model calls when
 * needed — and handle the function-calling loop.
 *
 * <p>You'll use: {@link FunctionToolDefinition}, the {@code requires_action} /
 * {@code submit_tool_outputs} run lifecycle, and a simple mock weather function.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module03ToolsAndFunctionCalling}
 */
public class Module03ToolsAndFunctionCalling {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mock weather data — in production this calls a real weather API. */
    private static final Map<String, Integer> WEATHER_CELSIUS = Map.of(
        "Zurich", 18,
        "Cairo",  34,
        "Oslo",    7
    );

    public static void main(String[] args) throws Exception {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Chat model : " + config.chatModel);

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        System.out.println("clients    : ready");
        System.out.println();

        // ── Define the get_weather function tool ──────────────────────────────
        FunctionToolDefinition getWeatherTool = buildGetWeatherTool();
        System.out.println("Declared tool: " + getWeatherTool.getFunction().getName());
        System.out.println();

        // ── Create the weather agent ───────────────────────────────────────────
        Agent weatherAgent = projectClient.getAgentsClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("weather-agent")
                .setInstructions("You are a travel assistant. Use the get_weather tool to " +
                    "answer weather questions; don't guess.")
                .setTools(List.of(getWeatherTool))
        );

        System.out.println("Agent created: " + weatherAgent.getName());
        System.out.println();

        // ── Run the function-calling loop ──────────────────────────────────────
        System.out.println("=== Function-calling loop ===");
        String userQuestion = "Should I pack a coat for Oslo? What's it like there now?";
        String reply = runWithTools(projectClient, weatherAgent, userQuestion);
        System.out.println(reply);

        projectClient.getAgentsClient().deleteAgent(weatherAgent.getId());
    }

    /**
     * Build the {@code get_weather} function tool declaration.
     *
     * <p>The schema is the contract the model reads. A crisp {@code description}
     * (per tool and per parameter) is the single biggest lever on whether the
     * model calls it correctly.
     */
    public static FunctionToolDefinition buildGetWeatherTool() {
        String parametersJson = """
            {
              "type": "object",
              "properties": {
                "city": {
                  "type": "string",
                  "description": "City name, e.g. 'Zurich'"
                },
                "unit": {
                  "type": "string",
                  "enum": ["celsius", "fahrenheit"],
                  "description": "Temperature unit"
                }
              },
              "required": ["city"]
            }
            """;

        return new FunctionToolDefinition(
            new FunctionDefinition("get_weather", BinaryData.fromString(parametersJson))
                .setDescription("Get the current weather for a city. " +
                    "Call this whenever a user asks about weather.")
        );
    }

    /**
     * Run an agent with tool support.  Loops until the run completes, executing
     * any function calls and submitting results back to Foundry.
     */
    public static String runWithTools(
            AIProjectClient client, Agent agent, String userMessage)
            throws Exception {

        AgentThread thread = client.getAgentsClient().createThread();
        client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, userMessage);

        ThreadRun run = client.getAgentsClient().createRun(
            thread.getId(), new CreateRunOptions(agent.getId())
        );

        while (true) {
            Thread.sleep(1_000);
            run = client.getAgentsClient().getRun(thread.getId(), run.getId());

            if (run.getStatus() == RunStatus.REQUIRES_ACTION) {
                run = handleToolCalls(client, thread.getId(), run);
            } else if (run.getStatus() == RunStatus.COMPLETED) {
                break;
            } else if (run.getStatus() != RunStatus.IN_PROGRESS
                    && run.getStatus() != RunStatus.QUEUED) {
                throw new RuntimeException("Run failed with status: " + run.getStatus());
            }
        }

        return extractLastAssistantMessage(client, thread.getId());
    }

    /**
     * Dispatch all pending function calls, collect results, and submit them back
     * to continue the run.
     */
    private static ThreadRun handleToolCalls(
            AIProjectClient client, String threadId, ThreadRun run)
            throws Exception {

        RequiredAction required = run.getRequiredAction();
        if (!(required instanceof SubmitToolOutputsAction submitAction)) {
            throw new RuntimeException("Unexpected required action type: " + required);
        }

        List<ToolOutput> toolOutputs = new ArrayList<>();
        for (RequiredFunctionToolCall call : submitAction.getSubmitToolOutputs().getToolCalls()) {
            String toolName = call.getFunction().getName();
            String argsJson = call.getFunction().getArguments();

            System.out.printf("[tool] %s(%s) -> ", toolName, argsJson);
            String result = dispatchTool(toolName, argsJson);
            System.out.println(result);

            toolOutputs.add(new ToolOutput(call.getId(), result));
        }

        return client.getAgentsClient().submitToolOutputsToRun(
            threadId, run.getId(), toolOutputs
        );
    }

    /** Route a tool call by name to the matching Java implementation. */
    private static String dispatchTool(String toolName, String argsJson) throws Exception {
        if ("get_weather".equals(toolName)) {
            JsonNode args = MAPPER.readTree(argsJson);
            String city = args.get("city").asText();
            String unit = args.has("unit") ? args.get("unit").asText() : "celsius";
            return getWeather(city, unit);
        }
        throw new IllegalArgumentException("Unknown tool: " + toolName);
    }

    /**
     * Mock weather implementation — replace with a real API call in production.
     */
    public static String getWeather(String city, String unit) {
        int tempC = WEATHER_CELSIUS.getOrDefault(city, 21);
        if ("fahrenheit".equalsIgnoreCase(unit)) {
            int tempF = Math.round(tempC * 9f / 5 + 32);
            return String.format("%s: %d°F, partly cloudy.", city, tempF);
        }
        return String.format("%s: %d°C, partly cloudy.", city, tempC);
    }

    private static String extractLastAssistantMessage(AIProjectClient client, String threadId) {
        List<ThreadMessage> messages = client.getAgentsClient()
            .listMessages(threadId).stream().toList();
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
