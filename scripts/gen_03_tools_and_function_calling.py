"""Generate Module 3 — Tools & Function Calling.

Distilled from upstream 08-agents/08-02 (Code Interpreter tool) and the
FunctionTool / function-call loop in 08-agents/08-08 (human-in-the-loop),
simplified to a single Foundry project + DefaultAzureCredential (no APIM
gateway, no {connection}/{model} prefix — the model is referenced by its
plain deployment name).
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M3 · Tools & Function Calling

> **Goal:** give an agent **tools** — first Foundry's hosted **Code Interpreter**, then a **custom function** of your own — and watch the model decide when to call them.
> **You'll use:** `CodeInterpreterTool`, `FunctionTool`, the `tools=[...]` field on `PromptAgentDefinition`, and the `function_call` → `function_call_output` loop.

---

The agent you built in """ + sibling_link("02-your-first-agent", "M2") + """ could only
*talk*. Tools let it **act** — run code, look things up, hit your APIs. Foundry
supports two flavours:

- **Hosted tools** (e.g. **Code Interpreter**) run *inside* Foundry. You attach them and
  the service executes them for you.
- **Custom function tools** run *in your code*. The model emits a structured call; you
  execute it and feed the result back. This is **function calling**.

![Anatomy of a Foundry agent](../../assets/agent-anatomy.png)

!!! note "Tool APIs are evolving"
    The agent/tool surface on Foundry is moving fast. The class names below mirror the
    current `azure-ai-projects` SDK; if an import differs in your version, check the
    package's `models` module — the *shapes* (a tool object in `tools=[...]`, a
    `function_call` item in the response) are stable."""),

    md("""\
## 1. Configure & build the client

The familiar bootstrap from """ + sibling_link("01-first-inference", "M1") + """. We
also keep a handle on `openai_client.files` (to hand data to Code Interpreter) and
`project_client.agents` (to define tool-equipped agents)."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module03ToolsAndFunctionCalling
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module03ToolsAndFunctionCalling.java"""),
    md("""\
!!! note "Expected output"
    ```
    Chat model : gpt-4.1-mini
    clients    : ready
    ```"""),

    md("""\
## 2. Upload data for Code Interpreter

Code Interpreter runs Python in a sandboxed container. To analyse a file, upload it
first with `purpose="assistants"`; the returned `file.id` is what you attach to the
agent. Here we synthesize a tiny CSV and upload it."""),
    code("""\
import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.FunctionToolDefinition;
import com.azure.ai.agents.persistent.models.FunctionDefinition;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.RequiredAction;
import com.azure.ai.agents.persistent.models.RequiredFunctionToolCall;
import com.azure.ai.agents.persistent.models.RequiredToolCall;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.SubmitToolOutputsAction;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.ToolDefinition;
import com.azure.ai.agents.persistent.models.ToolOutput;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;"""),
    md("""\
!!! note "Expected output"
    ```
    Uploaded file id: assistant-7xKQ...e2
    ```
    The file now lives in your project's Files store, ready for any agent you grant
    access to."""),

    md("""\
## 3. Create an agent with the Code Interpreter tool

Attach the hosted tool through the `tools=[...]` field on the agent definition.
`AutoCodeInterpreterToolParam` provisions a managed container and pre-loads the file
ids you pass — so the agent can read the CSV the moment it runs."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Agent   : data-analyst-agent
    Version : 1
    ```
    Same `create_version` pattern as """ + sibling_link("02-your-first-agent", "M2") + """ —
    tools are just another field on the definition, so they're versioned with it."""),

    md("""\
## 4. Let the agent run code

Ask a question that *requires* computation. The agent writes Python against the CSV,
runs it in the container, and returns the answer — you never see the code unless you
ask for it. Invocation is the same `agent_reference` call from M2."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Chat model : " + config.chatModel);

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();

System.out.println("clients    : ready");
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Q4 had the highest operating profit for TRANSPORTATION at 150. The full-year
    total across Q1–Q4 was 533.
    ```

!!! tip "Hosted = you don't run it"
    Code Interpreter executed entirely inside Foundry. Each conversation gets its own
    sandbox session (idle-timeout ~30 min). Charts and files it produces come back as
    `container_file_citation` annotations you can download — a great next experiment."""),

    md("""\
## 5. Define a custom function tool

For *your* logic, declare a `FunctionTool`: a name, a description, and a JSON-Schema for
its parameters. This is only a **declaration** — the model uses it to decide *when* and
*with what arguments* to call. The actual implementation stays in your code."""),
    code("""\
// ── Define the get_weather function tool ──────────────────────────────
FunctionToolDefinition getWeatherTool = buildGetWeatherTool();
System.out.println("Declared tool: " + getWeatherTool.getFunction().getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Declared tool: get_weather
    ```
    The schema is the contract the model reads. A crisp `description` (per tool *and*
    per parameter) is the single biggest lever on whether the model calls it correctly."""),

    md("""\
## 6. Wire the function-calling loop

Function tools need a round-trip: the model returns a **`function_call`** instead of
text, you execute it, then send the result back as a **`function_call_output`** keyed by
`call_id`. Linking calls with `previous_response_id` lets the agent continue where it
left off. Loop until no more tool calls remain."""),
    code("""\
// ── Create the weather agent ───────────────────────────────────────────
PersistentAgent weatherAgent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
    new CreateAgentOptions(config.chatModel)
        .setName("weather-agent")
        .setInstructions("You are a travel assistant. Use the get_weather tool to " +
            "answer weather questions; don't guess.")
        .setTools(List.of(getWeatherTool))
);

System.out.println("PersistentAgent created: " + weatherAgent.getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    [tool] get_weather({'city': 'Oslo'}) -> Oslo: 7°C, partly cloudy.

    Yes — pack a coat. It's about 7°C and partly cloudy in Oslo right now, so a warm
    layer will be welcome.
    ```
    The model chose to call `get_weather`, you executed it locally, and the agent wove
    the real result into a natural answer."""),

    md("""\
!!! warning "Validate tool arguments"
    The model proposes the arguments — treat them like any untrusted input. Validate
    and authorize before doing anything irreversible. The same loop shape extends to a
    **human-in-the-loop** gate: intercept sensitive calls, get approval, *then* run
    them."""),

    md("""\
## 🧪 Your turn

1. **Add a second function tool.** Declare `convert_currency(amount, from, to)`, attach
   it alongside `get_weather`, and ask a question that forces *both* calls in one turn.
   The loop already handles multiple `function_call` items per response.
2. **Make Code Interpreter draw.** Re-run section 4 asking for a **bar chart** PNG, then
   pull the `container_file_citation` annotation off `response.output[-1]` and download
   the bytes with `openai_client.containers.files.content.retrieve(...)`.
3. **Starve the model.** Remove `get_weather` from the `tools` list but keep the weather
   question — watch it either refuse or hedge, proving the tool (not the model) supplied
   the facts.

---

✅ **Your agent can now run hosted code and call your own functions.** Next: ground it in
*your* knowledge so its answers are backed by real sources.
""" + next_link("04-grounding-rag-foundry-iq", "M4 · Grounding / RAG (Foundry IQ)")),
]
    # Extra Java cells
    code("""\
// ── Run the function-calling loop ──────────────────────────────────────
System.out.println("=== Function-calling loop ===");
String userQuestion = "Should I pack a coat for Oslo? What's it like there now?";
String reply = runWithTools(projectClient, weatherAgent, userQuestion);
System.out.println(reply);

projectClient.getPersistentAgentsAdministrationClient().deleteAgent(weatherAgent.getId());"""),


write_notebook(
    "docs/modules/03-tools-and-function-calling.ipynb",
    cells,
)
