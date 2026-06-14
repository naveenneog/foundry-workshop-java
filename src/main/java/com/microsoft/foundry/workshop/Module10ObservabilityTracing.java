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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import com.azure.monitor.opentelemetry.exporter.AzureMonitorTraceExporter;
import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;

import java.util.List;

/**
 * M10 · Observability & Tracing
 *
 * <p>Goal: instrument your agent calls with OpenTelemetry tracing and export spans
 * to Azure Monitor (Application Insights) for continuous observability.
 *
 * <p>You'll use: OpenTelemetry SDK, {@link AzureMonitorTraceExporter},
 * and manual span wrapping around agent invocations.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module10ObservabilityTracing}
 *
 * <p>Prerequisites: set {@code APP_INSIGHTS_CONN_STRING} in {@code .env}.
 */
public class Module10ObservabilityTracing {

    private static Tracer tracer;

    public static void main(String[] args) throws InterruptedException {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project         : " + config.projectEndpoint);
        System.out.println("Chat            : " + config.chatModel);
        System.out.println("App Insights    : " +
            (config.appInsightsConnectionString.isBlank() ? "(not set)" : "configured"));
        System.out.println();

        // ── 1. Initialise OpenTelemetry ────────────────────────────────────────
        initTelemetry(config.appInsightsConnectionString);
        System.out.println("OpenTelemetry   : ready");
        System.out.println();

        // ── 2. Build clients ───────────────────────────────────────────────────
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        AIProjectClient projectClient = new AIProjectClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

        // ── 3. Create an agent ─────────────────────────────────────────────────
        Agent agent = projectClient.getAgentsClient().createAgent(
            new CreateAgentOptions(config.chatModel)
                .setName("traced-agent")
                .setInstructions("You are a helpful assistant. Answer concisely.")
        );

        System.out.println("Agent created: " + agent.getName());
        System.out.println();

        // ── 4. Invoke with tracing ─────────────────────────────────────────────
        System.out.println("=== Traced agent invocations ===");

        String[] questions = {
            "What is Microsoft Foundry in one sentence?",
            "Name the three most important Azure AI services."
        };

        for (String question : questions) {
            String reply = invokeWithTracing(projectClient, agent, question);
            System.out.println("Q: " + question);
            System.out.println("A: " + reply);
            System.out.println();
        }

        // Give the exporter time to flush spans to Application Insights
        Thread.sleep(5_000);
        ((OpenTelemetrySdk) GlobalOpenTelemetry.get()).getSdkTracerProvider().forceFlush();

        System.out.println("Spans exported. Check your Application Insights instance.");
        projectClient.getAgentsClient().deleteAgent(agent.getId());
    }

    /**
     * Initialise the OpenTelemetry SDK with an Azure Monitor exporter.
     *
     * <p>If {@code connectionString} is blank, a no-op SDK is used so the lab
     * still runs locally without an Application Insights resource.
     */
    public static void initTelemetry(String connectionString) {
        SdkTracerProvider tracerProvider;

        if (!connectionString.isBlank()) {
            AzureMonitorTraceExporter exporter = new AzureMonitorExporterBuilder()
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

        tracer = sdk.getTracer("foundry-workshop", "0.1.0");
    }

    /**
     * Wrap a single agent invocation in an OpenTelemetry span so it appears as a
     * named operation in Application Insights.
     */
    public static String invokeWithTracing(
            AIProjectClient client, Agent agent, String userMessage)
            throws InterruptedException {

        Span span = tracer.spanBuilder("agent.invoke")
            .setAttribute("agent.name", agent.getName())
            .setAttribute("user.message", userMessage)
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            AgentThread thread = client.getAgentsClient().createThread();
            client.getAgentsClient().createMessage(thread.getId(), MessageRole.USER, userMessage);

            ThreadRun run = client.getAgentsClient().createRun(
                thread.getId(), new CreateRunOptions(agent.getId())
            );

            while (run.getStatus() == RunStatus.IN_PROGRESS
                || run.getStatus() == RunStatus.QUEUED) {
                Thread.sleep(1_000);
                run = client.getAgentsClient().getRun(thread.getId(), run.getId());
            }

            if (run.getStatus() != RunStatus.COMPLETED) {
                span.setStatus(StatusCode.ERROR, "Run status: " + run.getStatus());
                throw new RuntimeException("Run failed: " + run.getStatus());
            }

            span.setAttribute("run.status", run.getStatus().toString());
            span.setStatus(StatusCode.OK);

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
        } finally {
            span.end();
        }
    }
}
