"""Generate Module 5 — MCP Tools.

Distilled from the upstream reference 08-agents/08-05-contoso-pmo-mcp
(08-05-01 agent-setup, 08-05-02 agent-queries, 08-05-03 tool-catalog, and the
08-05-00 MCP guide), simplified to a single Foundry project + DefaultAzureCredential.

The reference *builds and deploys* a 37-tool Azure Functions MCP server. Here we
ASSUME that server already exists — the lab reads its URL/label from `.env` — so
the focus stays on the SDK story: how an agent is given an MCP tool and how it
calls it.
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M5 · MCP Tools

> **Goal:** connect your agent to an **external system** through a Model Context Protocol (MCP) server — and watch it call real tools.
> **You'll use:** an `MCPTool` on a `PromptAgentDefinition`, the Responses API, and the **Foundry tool catalog**.

---

In """ + sibling_link("04-grounding-rag-foundry-iq", "M4") + """ you grounded an agent in
*documents*. But agents often need to reach *live systems* — a ticketing backend, a CRM,
a project tracker. **MCP (Model Context Protocol)** is the open standard for exactly that:
write your tools once as an MCP server, and any MCP-speaking agent can discover and call
them. The agent expresses **intent** ("what's overdue?"); the server owns the **endpoint**
details.

![Anatomy of a Foundry agent](../../assets/agent-anatomy.png)

!!! note "The MCP server is assumed to exist"
    The reference lab deploys a multi-tool **Azure Functions** MCP server. Provisioning
    is covered conceptually in the Platform docs — here we just read its URL and label
    from `.env` (`MCP_SERVER_URL`, `MCP_SERVER_LABEL`) and wire it to an agent. Foundry's
    versioned-agent API is **preview**; pin `azure-ai-projects` in `pyproject.toml` if a
    symbol drifts."""),

    md("""\
## 1. Configure

Beyond the usual project variables, we read the **MCP server endpoint** and a short
**server label**. The label namespaces the server's tools inside the agent, so multiple
MCP servers can coexist without collisions."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module05McpTools
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module05McpTools.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project : https://<account>.services.ai.azure.com/api/projects/<project>
    Model   : gpt-4.1-mini
    MCP url : https://<host>/runtime/webhooks/mcp/sse (+ key)
    Label   : project_tracker
    ```
    We print the URL **without** its secret query string — keep keys out of notebook
    output."""),

    md("""\
## 2. Build the client

The same bootstrap as every lab: one credential, one project client, one
OpenAI-compatible client. The Responses API on `openai_client` is how we'll invoke the
agent once its MCP tool is attached."""),
    code("""\
import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.ai.agents.persistent.models.OpenApiAnonymousAuthDetails;
import com.azure.ai.agents.persistent.models.OpenApiFunctionDefinition;
import com.azure.ai.agents.persistent.models.OpenApiToolDefinition;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.util.List;"""),
    md("""\
!!! note "Expected output"
    ```
    project_client : ready
    openai_client  : ready
    ```"""),

    md("""\
## 3. Attach the MCP server to an agent

You give an agent an MCP server by adding **one tool definition** to its
`PromptAgentDefinition`. The minimal shape is a dict with `type: "mcp"`, the
`server_label`, and the `server_url`. Setting `require_approval="never"` lets the agent
call tools without pausing for a human OK — fine for trusted, read-mostly servers."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Agent 'pm-assistant' ready (version 1).
    ```
    The agent definition now *contains* the MCP server. On every run, Foundry connects
    to the server, fetches its tool list (names, descriptions, parameter schemas), and
    hands those to the model — this is **tool discovery**, and it happens automatically."""),

    md("""\
## 4. Ask the agent — and watch it call a tool

Invoke the agent through the Responses API with an `agent_reference`. Behind the scenes
the model reads the available tools, decides which to call, sends the call to the MCP
server, and folds the result into its answer. The tools it invoked show up as **`mcp_call`
items** in `response.output`."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Project    : " + config.projectEndpoint);
System.out.println("Chat       : " + config.chatModel);
System.out.println("MCP server : " + MCP_SERVER_URL);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
PersistentAgentsClient client = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Tools called: ['list_overdue_tasks']
    Three tasks are overdue:
      • Finalize launch copy — owner: Priya Nair (due 2026-06-05)
      • Vendor security review — owner: Tom Alvarez (due 2026-06-08)
      • Pricing sign-off — owner: Dana Lee (due 2026-06-10)
    ```
    The model translated plain English into a `list_overdue_tasks` call — **intent over
    endpoint**. You never wrote an HTTP request; the MCP server owns that detail."""),

    md("""\
## 5. Inspect tool discovery

Curious which tools the server actually offers? They surface as **`mcp_list_tools`**
items in the response output the first time the agent connects. Printing them shows the
exact catalog the model chose from — useful when an agent *isn't* calling the tool you
expected."""),
    code("""\
// ── 1. Build an OpenAPI tool backed by the MCP server spec ────────────
String spec = String.format(MCP_OPENAPI_SPEC, MCP_SERVER_URL);
OpenApiToolDefinition mcpTool = new OpenApiToolDefinition(
    new OpenApiFunctionDefinition(
        "workshop-mcp",
        BinaryData.fromString(spec),
        new OpenApiAnonymousAuthDetails()
    ).setDescription("Tools exposed by the workshop MCP server.")
);

System.out.println("OpenAPI/MCP tool declared: " + mcpTool.getOpenapi().getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Server 'project_tracker' exposes 12 tools:
      - list_projects
      - get_project
      - list_overdue_tasks
      - search_documents
      - flag_risk
    ```

!!! tip "Design tools around intent, not endpoints"
    Good MCP tools mirror what a *user* wants — `list_overdue_tasks`, `search_documents`
    — not raw CRUD over a database. The model picks tools from their **names and
    descriptions**, so intent-shaped names dramatically improve routing accuracy."""),

    md("""\
## 6. Reference the server from the tool catalog

Embedding `server_url` (with its key) in every agent doesn't scale — rotate the key and
you must edit each agent. The **Foundry tool catalog** fixes this: register the MCP
server **once** as a project connection, then agents reference it by **connection id**.
The credential lives in the connection, not the agent definition."""),
    code("""\
// ── 2. Create an agent with the MCP/OpenAPI tool ───────────────────────
PersistentAgent agent = client.getPersistentAgentsAdministrationClient().createAgent(
    new CreateAgentOptions(config.chatModel)
        .setName("mcp-demo-agent")
        .setInstructions("You are a helpful assistant. Use the tools available " +
            "to you to answer questions. When a tool is relevant, call it.")
        .setTools(List.of(mcpTool))
);

System.out.println("Agent created: " + agent.getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Agent 'pm-assistant' now at version 2.
    Tool source: catalog connection 'project-tracker-mcp' (no key in the definition).
    ```
    `create_version` is idempotent on *content*: because we changed the tool wiring, a
    **new version (2)** is minted. Rotate the server's key in the connection and **every**
    agent that references it picks up the change — zero agent edits.

!!! warning "When to require approval"
    `require_approval="never"` suits read-mostly, trusted servers. For tools that **write**
    or spend money, set `require_approval="always"` so each call surfaces for a human to
    approve before it runs."""),

    md("""\
## 🧪 Your turn

1. **Ask a multi-step question.** Prompt the agent with something that needs two tools
   (e.g. *"Find overdue tasks on the Aurora project and flag a risk for the latest one"*)
   and print `tools` — you should see more than one `mcp_call`.
2. **Tighten the instructions.** Edit the system prompt to require the agent to cite the
   **task id** for every item, re-version, and re-run. Notice the format change.
3. **Flip approval on.** Re-create the agent with `require_approval="always"` and observe
   the `mcp_approval_request` item that appears in `response.output` instead of an
   immediate tool call.

---

✅ **You connected an agent to a remote MCP server, watched it discover and call tools,
and moved the credential into the tool catalog.** Next: give your agent **memory** so it
remembers across turns.
""" + next_link("06-agent-memory", "M6 · Agent Memory")),
]
    # Extra Java cells
    code("""\
// ── 3. Invoke via standard thread / run lifecycle ──────────────────────
System.out.println("=== MCP agent query ===");
String reply = invokeAgent(client, agent,
    "What is the current time in UTC? Use any available tool to find out.");
System.out.println(reply);

client.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());"""),


write_notebook(
    "docs/modules/05-mcp-tools.ipynb",
    cells,
)
