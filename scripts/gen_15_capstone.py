"""Generate Module 15 — Capstone.

Synthesizes the whole workshop: build ONE agent that is grounded (M4),
tool-using (M3), evaluated (M9), and observable (M10) — then point to the
enterprise topics the workshop deliberately deferred.
"""
from nbbuild import md, code, write_notebook, sibling_link, page_link

cells = [
    md("""\
# M15 · Capstone

> **Goal:** combine everything — a grounded, tool-using, evaluated, observable agent — into one coherent build, then see where to go next.
> **You'll use:** `PromptAgentDefinition` with tools + knowledge, the Responses API, an evaluator, and tracing.

---

This is the victory lap. You've built each capability in isolation; now you'll wire
the important ones into a **single agent** and run it end to end. Then we'll map the
enterprise topics this workshop deliberately kept out of your way.

![Microsoft Foundry — one unified platform](../../assets/platform-overview.png)

!!! tip "What we're assembling"
    A **"Contoso Support"** agent that:

    - is **grounded** on a small knowledge base (""" + sibling_link("04-grounding-rag-foundry-iq", "M4") + """),
    - can call a **custom tool** (""" + sibling_link("03-tools-and-function-calling", "M3") + """),
    - is **evaluated** for quality before we trust it (""" + sibling_link("09-evaluation", "M9") + """),
    - and is **traced** so we can watch it in production (""" + sibling_link("10-observability-tracing", "M10") + """)."""),

    md("""\
## 1. Bootstrap (the pattern you now know by heart)

Same four lines from """ + sibling_link("01-first-inference", "M1") + """ — one client,
reused for everything."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module15Capstone
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module15Capstone.java"""),
    md("""\
!!! note "Expected output"
    ```
    Ready to build the capstone agent on: gpt-4.1-mini
    ```"""),

    md("""\
## 2. A tool the agent can call

We give the support agent one **custom function tool** — looking up an order's status —
exactly as you did in """ + sibling_link("03-tools-and-function-calling", "M3") + """.
In a real build this would hit your order system; here it's a stub."""),
    code("""\
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
import java.util.Map;"""),
    md("""\
!!! note "Expected output"
    ```
    Tool defined: get_order_status
    ```"""),

    md("""\
## 3. Define the capstone agent

We create a **versioned agent** (""" + sibling_link("02-your-first-agent", "M2") + """)
whose definition carries both **instructions** and the **tool**. In a full build you'd
also attach a Foundry IQ **knowledge base** (""" + sibling_link("04-grounding-rag-foundry-iq", "M4") + """)
here so answers are grounded with citations."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Name    : contoso-support-agent
    Version : 1
    ```

!!! warning "Tool + knowledge APIs are evolving"
    The exact `tools` / `knowledge` field shapes on `PromptAgentDefinition` are
    pre-release. If an import or field name fails, re-check """ +
    sibling_link("03-tools-and-function-calling", "M3") + """ and """ +
    sibling_link("04-grounding-rag-foundry-iq", "M4") + """, and pin versions in
    `pyproject.toml`."""),

    md("""\
## 4. Run it — with the tool-call loop

Invoke through the Responses API. If the model decides to call our tool, we run the
function locally and feed the result back so it can finish its answer — the
`function_call → function_call_output` loop from """ +
sibling_link("13-human-in-the-loop-and-rest", "M13") + """."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("=== Capstone: Enterprise AI PersistentAgent ===");
System.out.println("Project : " + config.projectEndpoint);
System.out.println("Chat    : " + config.chatModel);
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Your order A-1001 has shipped and is expected to arrive on 2026-06-15.
    Is there anything else I can help you with?
    ```
    The model called `get_order_status("A-1001")`, we returned the stub data, and it
    composed the final reply from that tool result."""),

    md("""\
## 5. Evaluate before you trust it

A capstone agent isn't done until it's **measured** (""" +
sibling_link("09-evaluation", "M9") + """). Score a couple of responses for
**relevance** against a tiny inline test set."""),
    code("""\
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
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Where is my order A-1001?      relevance = 5/5
    What's the ETA on A-1002?      relevance = 4/5
    ```
    Scores will vary. The point: you have a **number** to gate releases on, not a vibe."""),

    md("""\
## 6. Make it observable

Finally, turn on tracing (""" + sibling_link("10-observability-tracing", "M10") + """)
so every capstone run emits spans to **Application Insights**. One call wires it up;
after that your `run_support(...)` calls are traced automatically."""),
    code("""\
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
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Tracing on — capstone runs now export spans to App Insights.
    ```
    In the portal's **Monitor** tab (or via KQL) you'll see a span per `responses.create`
    call, including the tool call — the full picture of what your agent did."""),

    md("""\
## 🧪 Your turn — make it yours

1. **Ground it for real.** Attach a Foundry IQ knowledge base from """ +
sibling_link("04-grounding-rag-foundry-iq", "M4") + """ and add a question whose
answer must come from a document — confirm the agent cites it.
2. **Add a guardrail.** Pin a guardrail policy from """ +
sibling_link("11-guardrails", "M11") + """ to the deployment and try a prompt-injection
input; confirm it's blocked.
3. **Harden + measure.** Run the """ + sibling_link("12-red-teaming", "M12") + """ scan
against your capstone agent, then add the worst-scoring prompts to your """ +
sibling_link("09-evaluation", "M9") + """ test set and re-evaluate."""),

    md("""\
## 🚀 Where to go next

You built the *application* layer end to end. The reference series this workshop draws
from goes deeper on the **enterprise platform** — pick your next thread:

| Topic | What it adds | Start with |
|:--|:--|:--|
| **Hosted agents** | Deploy your agent as a containerized (ACR-backed) service for portability and scale. | Reference lab `08-03-hosted-agents` |
| **Multi-agent at scale** | Grow """ + sibling_link("07-multi-agent-orchestration", "M7") + """ into a production router + specialist fleet. | Reference area `11` |
| **Content Understanding** | Plumb Azure AI Content Understanding (documents, audio, video) behind your project. | Reference area `09` |
| **Hub-and-spoke infra** | The Bicep/APIM topology, per-team quotas, and a governed gateway from """ + page_link("concepts", "Concepts") + """. | Reference area `05` |
| **Governance with policy** | Deny ungoverned deployments and force all traffic through the gateway. | Reference area `06` |
| **Publishing** | Surface your agent in Microsoft 365, Teams, and BizChat. | Control plane docs |

Read the """ + page_link("concepts", "Concepts") + """ page once more — now every box in
that diagram is something you've actually built.

---

✅ **You shipped a grounded, tool-using, evaluated, observable agent on Microsoft
Foundry — end to end.** That's the whole workshop. Nicely done.

← Back to """ + page_link("index", "the workshop home") + """ · revisit any lab from there."""),
]
    # Extra Java cells
    code("""\
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
System.out.println("Capstone complete! See Application Insights for traces.");"""),


write_notebook(
    "docs/modules/15-capstone.ipynb",
    cells,
)
