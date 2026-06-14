"""Generate Module 7 — Multi-Agent Orchestration.

Distilled from the upstream reference 11-foundry-iq-multi-agent
(11-04-multi-agent-setup.ipynb, 11-05-multi-agent-queries.ipynb, and the
agents/ package: orchestrator.py, hr_agent.py, marketing_agent.py,
products_agent.py), simplified to a single Foundry project + DefaultAzureCredential.

The reference routes every agent's inference through an APIM connection
(`FoundryChatClient(model="{connection}/{model}")`) and grounds each specialist
on a per-domain Foundry IQ knowledge base via `AzureAISearchContextProvider`
(the `agent-framework-azure-ai-search` extra). In our single-project world the
model is reachable directly, so specialists use `AzureAIAgentClient` over the
project endpoint with plain deployment names — and we teach the **router +
specialists** routing pattern with the real `WorkflowBuilder` wiring. Grounding
is shown as a one-note production upgrade (it's the M4 story) so the lesson stays
on orchestration.
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M7 · Multi-Agent Orchestration

> **Goal:** coordinate a team of specialist agents behind a **router** — one classifies intent, three answer in their domain.
> **You'll use:** the **Microsoft Agent Framework** — `AzureAIAgentClient`, `Agent`, and `WorkflowBuilder` switch-case routing.

---

A single do-everything agent gets brittle fast: its instructions bloat, its tools
collide, and its answers blur across domains. The fix is the **router + specialists**
pattern — a small **orchestrator** classifies each question and dispatches it to exactly
one **domain expert** (HR, Marketing, or Products). Each specialist stays sharp because it
owns one job.

![Router dispatching to domain specialists](../../assets/multi-agent-router.png)

You'll build this with the **Microsoft Agent Framework**'s `WorkflowBuilder`: a typed graph
where the orchestrator is the **start node** and a **switch-case edge group** routes its
classification to one of three specialist nodes.

!!! note "The Agent Framework is pre-1.0"
    This lab uses `agent-framework-core` + `agent-framework-azure-ai` (pinned to
    **`1.0.0rc6`** in `pyproject.toml`). The reference routes inference through an APIM
    connection with `FoundryChatClient`; our single-project lab uses `AzureAIAgentClient`
    over the project endpoint with plain deployment names. Symbols move between release
    candidates — if an import drifts, pin the version and check what the package actually
    exports."""),

    md("""\
## 1. Configure

The usual project variables, plus `SEARCH_ENDPOINT` — read here only so you can later
**ground** each specialist on a Foundry IQ knowledge base (see the upgrade note in §3).
The routing pattern itself needs nothing beyond the chat model."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module07MultiAgentOrchestration
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module07MultiAgentOrchestration.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project : https://<account>.services.ai.azure.com/api/projects/<project>
    Model   : gpt-4.1-mini
    Search  : (not set — specialists run ungrounded)
    ```
    Every agent in this lab shares one chat model — the specialisation comes from
    **instructions and routing**, not from different models."""),

    md("""\
## 2. Build the Agent Framework client

The Agent Framework wraps your Foundry project in an **`AzureAIAgentClient`**. We build it
from the same `AIProjectClient` you've used all workshop — one credential, one project, one
client — then hand that client to every agent we create. `Agent` is the framework's
unit: a model client + instructions + a name."""),
    code("""\
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
import java.util.List;"""),
    md("""\
!!! note "Expected output"
    ```
    project_client : ready
    chat_client    : ready (AzureAIAgentClient)
    ```
    `AzureAIAgentClient` is the single-project stand-in for the reference's
    APIM-routed `FoundryChatClient` — same role, no gateway prefix on the model name."""),

    md("""\
## 3. Define the specialists

Three domain experts, each a plain `Agent` with a focused system prompt. Tight instructions
are what keep a specialist on-topic — the HR agent won't wander into product specs. We give
each a clear `name` (the router's targets) and `description`."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
      contoso-hr-agent
      contoso-marketing-agent
      contoso-products-agent
    ```

!!! tip "Grounding a specialist (production upgrade)"
    In the reference, each specialist is grounded on a **Foundry IQ knowledge base** so its
    answers cite real documents. Attach a context provider (needs the
    `agent-framework-azure-ai-search` extra and a KB provisioned per
    """ + sibling_link("04-grounding-rag-foundry-iq", "M4") + """):
    ```python
    from agent_framework.azure import AzureAISearchContextProvider
    kb = AzureAISearchContextProvider(
        endpoint=SEARCH_ENDPOINT, credential=credential, mode="agentic",
        knowledge_base_name="contoso-kb-hr",
        knowledge_base_output_mode="answer_synthesis",
    )
    Agent(client=chat_client, name="contoso-hr-agent", instructions=..., context_providers=[kb])
    ```
    The routing pattern below is identical whether specialists are grounded or not."""),

    md("""\
## 4. Define the orchestrator (router)

The orchestrator is itself an `Agent` — but a tiny one. Its only job is **classification**:
read the question, emit exactly one label (`HR`, `MARKETING`, or `PRODUCTS`). No prose, no
answer. Constraining the output to a single word makes routing reliable."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Project : " + config.projectEndpoint);
System.out.println("Model   : " + config.chatModel);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Classification: HR
    ```
    A focused classifier is cheap and fast — it emits one token of signal, not a full
    answer. That single word is what the workflow switches on next."""),

    md("""\
## 5. Wire the workflow graph

Now the orchestration. `WorkflowBuilder` builds a typed graph: the **orchestrator** is the
start node, and an **`add_switch_case_edge_group`** routes its output to the first matching
`Case` — or the `Default`. The case conditions inspect the classifier's text (which arrives
wrapped as an `AgentExecutorResponse`, so we read `.agent_response.text`)."""),
    code("""\
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
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Workflow built — orchestrator → {hr | marketing | products}
    ```
    `Default` is the safety net: anything the classifier doesn't tag HR or MARKETING falls
    through to Products. Order matters — the **first** matching `Case` wins."""),

    md("""\
## 6. Route real queries through the team

Invoke the wrapped workflow exactly like a single agent — `await team.run(question)` — and
the graph does the rest: classify, dispatch, answer. Send one question per domain and watch
each land on the right specialist."""),
    code("""\
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
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Q: What is Contoso's remote work policy?
    A: Contoso supports a hybrid model: employees may work remotely up to three days per
       week with manager approval, with core collaboration hours from 10am–2pm...

    Q: What are the key elements of the Contoso brand guidelines?
    A: The Contoso brand centres on the primary blue palette, the Segoe typeface, generous
       whitespace, and a confident-but-approachable voice across all channels...

    Q: What are the specifications of the ContosoBook Pro laptop?
    A: The ContosoBook Pro ships with a 14" Retina display, the Contoso M3 chip, 18 hours
       of battery, 16–32GB RAM, and up to 2TB SSD...
    ```
    Each answer came from a **different** specialist — the orchestrator routed HR →
    `contoso-hr-agent`, brand → `contoso-marketing-agent`, specs → `contoso-products-agent`,
    with no manual dispatch in your code.

!!! tip "Why a graph beats an if/else"
    You *could* hand-write the routing with an `if label == 'HR'`. The `WorkflowBuilder`
    graph pays off as the team grows: add a node, add a `Case`, and you get typed edges,
    a visualisable topology, and a single `team.run(...)` surface — the same primitive
    that scales to fan-out, loops, and sub-workflows."""),

    md("""\
## 🧪 Your turn

1. **Add a fourth specialist.** Create a `contoso-it-agent` (helpdesk / device support), add
   an `IT` label to the orchestrator's instructions, and add a `Case(condition=_is_it,
   target=it_agent)` — then ask *"My laptop won't connect to VPN, who do I contact?"*
2. **Probe the Default branch.** Ask something genuinely ambiguous (*"Tell me about Contoso"*)
   and confirm it falls through to the Products catch-all. Then tighten the orchestrator
   prompt to handle it better.
3. **Ground a specialist.** If you have a Foundry IQ KB from """ +
   sibling_link("04-grounding-rag-foundry-iq", "M4") + """, attach an
   `AzureAISearchContextProvider` to the HR agent (see §3) and confirm its answers now cite
   document titles.

---

✅ **You built a router that classifies intent and a `WorkflowBuilder` graph that dispatches
to three domain specialists — multi-agent orchestration in ~40 lines.** Next: a single agent
that plans and iterates like a research analyst.
""" + next_link("08-deep-research", "M8 · Deep Research")),
]
    # Extra Java cells
    code("""\
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
projectClient.getPersistentAgentsAdministrationClient().deleteAgent(productsAgent.getId());"""),


write_notebook(
    "docs/modules/07-multi-agent-orchestration.ipynb",
    cells,
)
