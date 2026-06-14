package com.microsoft.foundry.workshop;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M15 · Capstone
 *
 * <p>Combine everything you've built across all previous modules into one
 * end-to-end enterprise AI agent:
 *
 * <ol>
 *   <li>Guardrails on input (M11)</li>
 *   <li>Multi-domain routing (M7)</li>
 *   <li>Tool-using specialist agent with function calling (M3)</li>
 *   <li>Persistent thread memory (M6)</li>
 *   <li>OpenTelemetry tracing to Application Insights (M10)</li>
 *   <li>LLM-as-judge quality check on the final answer (M9)</li>
 * </ol>
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module15Capstone}
 */
public class Module15Capstone {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Tracer tracer;

    public static void main(String[] args) throws Exception {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("=== Capstone: Enterprise AI PersistentAgent ===");
        System.out.println("Project : " + config.projectEndpoint);
        System.out.println("Chat    : " + config.chatModel);
        System.out.println();

        // ── Initialise telemetry ───────────────────────────────────────────────
        initTracing(config.appInsightsConnectionString);

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();
        OpenAIClient openAIClient = new OpenAIClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── Create the capstone agent (tools + memory via persistent thread) ──
        FunctionToolDefinition weatherTool = Module03ToolsAndFunctionCalling.buildGetWeatherTool();
        FunctionToolDefinition exchangeTool = buildExchangeRateTool();

        PersistentAgent agent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("capstone-agent")
                .setInstructions(
                    "You are an enterprise travel-planning assistant. " +
                    "Use the get_weather tool for weather questions and the " +
                    "get_exchange_rate tool for currency questions. " +
                    "Remember context across turns."
                )
                .setTools(List.of(weatherTool, exchangeTool))
        );

        // Persistent thread — reused across turns for memory (M6)
        PersistentAgentThread thread = projectClient.getThreadsClient().createThread();

        System.out.println("PersistentAgent and thread ready. Starting conversation...");
        System.out.println();

        // ── Multi-turn conversation ─────────────────────────────────────────────
        List<String> userTurns = List.of(
            "I'm planning a trip. My name is Sam.",
            "What's the weather like in Zurich and Oslo?",
            "What is the USD to EUR exchange rate?",
            "Given the weather and currency info, which city should I visit first?"
        );

        Module09Evaluation.LlmJudge judge =
            new Module09Evaluation.LlmJudge(openAIClient, config.chatModel);

        for (String userMessage : userTurns) {
            System.out.println("User: " + userMessage);

            // Layer 1: input guardrails
            Module11Guardrails.GuardrailResult guard = Module11Guardrails.checkInput(userMessage);
            if (!guard.allowed()) {
                System.out.println("BLOCKED: " + guard.reason());
                System.out.println();
                continue;
            }

            // Layer 2: invoke agent with tracing
            String reply = invokeWithTracing(projectClient, agent, thread, userMessage);

            // Layer 3: output guardrails
            Module11Guardrails.GuardrailResult outGuard = Module11Guardrails.checkOutput(reply);
            if (!outGuard.allowed()) {
                System.out.println("OUTPUT BLOCKED: " + outGuard.reason());
                System.out.println();
                continue;
            }

            System.out.println("Agent: " + reply);

            // Layer 4: LLM-as-judge quality check on substantive answers
            if (reply.length() > 50) {
                double relevance = judge.relevance(userMessage, reply);
                System.out.printf("  [quality] relevance=%.1f/5%n", relevance);
            }
            System.out.println();
        }

        projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());
        System.out.println("Capstone complete! See Application Insights for traces.");
    }

    private static FunctionToolDefinition buildExchangeRateTool() {
        return new FunctionToolDefinition(
            new FunctionDefinition("get_exchange_rate", BinaryData.fromString("""
                {
                  "type": "object",
                  "properties": {
                    "from_currency": {"type": "string", "description": "Source currency code, e.g. USD"},
                    "to_currency":   {"type": "string", "description": "Target currency code, e.g. EUR"}
                  },
                  "required": ["from_currency", "to_currency"]
                }
                """))
                .setDescription("Get the current exchange rate between two currencies.")
        );
    }

    private static String dispatchTool(String toolName, String argsJson) throws Exception {
        JsonNode args = MAPPER.readTree(argsJson);
        return switch (toolName) {
            case "get_weather" -> {
                String city = args.get("city").asText();
                String unit = args.has("unit") ? args.get("unit").asText() : "celsius";
                yield Module03ToolsAndFunctionCalling.getWeather(city, unit);
            }
            case "get_exchange_rate" -> {
                String from = args.get("from_currency").asText();
                String to   = args.get("to_currency").asText();
                // Mock rates — replace with a real FX API in production
                Map<String, Double> rates = Map.of(
                    "USD_EUR", 0.92, "EUR_USD", 1.09,
                    "USD_CHF", 0.89, "CHF_USD", 1.12
                );
                double rate = rates.getOrDefault(from + "_" + to, 1.0);
                yield String.format("1 %s = %.4f %s (indicative rate)", from, rate, to);
            }
            default -> "Tool '" + toolName + "' not implemented.";
        };
    }

    private static String invokeWithTracing(
            PersistentAgentsClient client, PersistentAgent agent, PersistentAgentThread thread, String userMessage)
            throws Exception {

        Span span = tracer.spanBuilder("capstone.turn")
            .setAttribute("user.message", userMessage)
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            client.getMessagesClient().createMessage(thread.getId(), MessageRole.USER, userMessage);
            ThreadRun run = client.getRunsClient().createRun(
                new CreateRunOptions(thread.getId(), agent.getId()));

            while (true) {
                Thread.sleep(1_000);
                run = client.getRunsClient().getRun(thread.getId(), run.getId());

                if (run.getStatus() == RunStatus.REQUIRES_ACTION) {
                    SubmitToolOutputsAction action = (SubmitToolOutputsAction) run.getRequiredAction();
                    List<ToolOutput> outputs = new ArrayList<>();
                    for (RequiredToolCall _toolCall : action.getSubmitToolOutputs().getToolCalls()) {
                        RequiredFunctionToolCall call = (RequiredFunctionToolCall) _toolCall;
                        String result = dispatchTool(call.getFunction().getName(), call.getFunction().getArguments());
                        System.out.printf("  [tool] %s -> %s%n", call.getFunction().getName(), result);
                        outputs.add(new ToolOutput().setToolCallId(call.getId()).setOutput(result));
                    }
                    run = client.getRunsClient().submitToolOutputsToRun(thread.getId(), run.getId(), outputs);
                } else if (run.getStatus() == RunStatus.COMPLETED) {
                    span.setStatus(StatusCode.OK);
                    break;
                } else if (run.getStatus() != RunStatus.IN_PROGRESS && run.getStatus() != RunStatus.QUEUED) {
                    span.setStatus(StatusCode.ERROR, run.getStatus().toString());
                    throw new RuntimeException("Run failed: " + run.getStatus());
                }
            }
        } finally {
            span.end();
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

    private static void initTracing(String connectionString) {
        SdkTracerProvider tracerProvider;
        if (!connectionString.isBlank()) {
            SpanExporter exporter = new AzureMonitorExporterBuilder()
                .connectionString(connectionString)
                .buildTraceExporter();
            tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
        } else {
            tracerProvider = SdkTracerProvider.builder().build();
        }
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
        tracer = sdk.getTracer("capstone", "0.1.0");
    }
}
