"""Generate Module 6 — Agent Memory.

Distilled from the upstream reference 08-agents/08-04-agent-memory
(08-04-01-deploy-agent-memory.ipynb + memory_helpers.py), simplified to a single
Foundry project + DefaultAzureCredential.

The reference deploys a *dedicated* Foundry account with local model deployments via
Bicep (because the Memory API can't use BYO/gateway models). In our single-project
world the model is already deployed locally on the project — so memory_search works
out of the box and we skip all the Bicep. We assume the memory-capable project exists
and read its store name from .env; the lab teaches the Memory API + the memory_search
agent tool.
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M6 · Agent Memory

> **Goal:** give your agent **memory** — so it recalls a user's context across turns and even across sessions.
> **You'll use:** Foundry's **Memory API** (memory stores) and the agent **`memory_search`** tool.

---

The agents you've built so far are **stateless** — each call starts from a blank slate.
Real assistants remember: *"you prefer Python", "you're planning the Aurora launch".*
Foundry's **Memory API** gives an agent a durable, per-user **memory store**: it extracts
salient facts from conversations, indexes them semantically, and lets the agent search
them on later turns.

The arc: **create a store → write memories → recall them → let an agent do it
automatically.**

![Anatomy of a Foundry agent](../../assets/agent-anatomy.png)

!!! note "Provisioning is conceptual here"
    The Memory API needs a chat + embedding model **deployed on the project's own
    account** (it can't use a gateway/BYO model). Our single-project setup already
    satisfies that — so there's no infrastructure to stand up; we just create a *store*
    inside the existing project. The Memory API is **preview** (api-version
    `2025-11-15-preview`); pin `azure-ai-projects` in `pyproject.toml` if a shape drifts."""),

    md("""\
## 1. Configure

Alongside the usual project variables, we name a **memory store** and a **user scope**.
The scope is the isolation key — each user's memories live under their own scope, so one
user never sees another's."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module06AgentMemory
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module06AgentMemory.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project : https://<account>.services.ai.azure.com/api/projects/<project>
    Store   : dev-prefs-memory
    Scope   : user_dana
    Models  : gpt-4.1-mini + text-embedding-3-large
    ```
    The store's internal chat/embedding models are what it uses to *extract* and *index*
    memories — separate from whatever model an agent later runs on."""),

    md("""\
## 2. Build the clients

The familiar bootstrap — one credential, the project client, and the OpenAI-compatible
client we'll use to invoke the memory-equipped agent later."""),
    code("""\
import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.util.List;"""),
    md("""\
!!! note "Expected output"
    ```
    project_client : ready
    openai_client  : ready
    ```"""),

    md("""\
## 3. A tiny Memory API client

The Memory API is a preview **REST** surface (no dedicated SDK class yet), so we wrap it
in a small helper. Two details matter: it uses the **`https://ai.azure.com`** token
audience (not the management plane), and write operations are **async** — you poll an
update id until it completes."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    memory client : ready
    ```
    A `401` here usually means the wrong token audience — confirm it's
    `https://ai.azure.com/.default`, not the management endpoint."""),

    md("""\
## 4. Create a memory store

The store is the per-project container for memories. `user_profile_enabled` tells it to
maintain a structured profile per scope; `chat_summary_enabled` lets it summarise
conversations into durable facts. It uses the models you pass to do that extraction."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Project : " + config.projectEndpoint);
System.out.println("Chat    : " + config.chatModel);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Memory store 'dev-prefs-memory' created.
      chat model      : gpt-4.1-mini
      embedding model : text-embedding-3-large
    ```
    `create_store` deletes any existing store of the same name first, so this cell is
    safe to re-run while iterating."""),

    md("""\
## 5. Turn 1 — write memories

Feed the store a short conversation. Its model reads the exchange and **extracts durable
facts** (not the raw transcript) under the user's scope. We format messages with a tiny
helper that matches the Memory API's `input_text` / `output_text` shape."""),
    code("""\
// ── 1. Create a stateful agent ─────────────────────────────────────────
PersistentAgent agent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
    new CreateAgentOptions(config.chatModel)
        .setName("memory-agent")
        .setInstructions("You are a helpful assistant with a good memory. " +
            "Remember what the user tells you across turns and reference " +
            "it naturally in later replies.")
);

System.out.println("PersistentAgent created: " + agent.getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Memories extracted:
      • Prefers programming in Python
      • Likes short, code-first answers
      • Uses VS Code on macOS
    ```
    Notice it stored **facts**, not the verbatim sentence — that's the extraction step
    doing its job. The write is async; our helper polled until it completed."""),

    md("""\
## 6. Recall — search the memories

Querying the store by scope returns the facts most relevant to the query. This is the
exact retrieval an agent will perform under the hood — and because results are
**scoped**, a different user's query would return their own memories, never Dana's."""),
    code("""\
// ── 2. Create one thread and reuse it for all turns ────────────────────
PersistentAgentThread thread = projectClient.getThreadsClient().createThread();
System.out.println("Thread id: " + thread.getId());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Recalled for user_dana :
      • Prefers programming in Python
      • Likes short, code-first answers
      • Uses VS Code on macOS
    ```

!!! tip "Scope is the isolation boundary"
    Memories never leak across scopes. In production you set `scope="{{$userId}}"` in the
    agent definition and Foundry resolves it **server-side** from each caller's Entra
    token — so every signed-in user automatically gets their own isolated memory."""),

    md("""\
## 7. Give an agent memory — recall across turns

Now the payoff. Attach the **`memory_search`** tool to an agent, pointed at the store and
scope. The agent automatically searches memory before answering **and** writes new
memories after (`update_delay` controls the lag). Watch it carry context across two
*separate* Responses API calls — no chat history passed between them."""),
    code("""\
// ── 3. Multi-turn conversation on the SAME thread ──────────────────────
System.out.println("=== Turn 1 ===");
String r1 = sendTurn(projectClient, thread, agent,
    "My name is Alex and I'm building an AI travel planner.");
System.out.println(r1);
System.out.println();

System.out.println("=== Turn 2 ===");
String r2 = sendTurn(projectClient, thread, agent,
    "What's my name and what am I building?");
System.out.println(r2);
System.out.println();

System.out.println("=== Turn 3 ===");
String r3 = sendTurn(projectClient, thread, agent,
    "What are three tips for building a great AI travel planner?");
System.out.println(r3);
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Agent 'dev-buddy' ready (version 1).

    Since you're in Python and like it code-first, here's the concise way:

        import json
        data = json.loads(text)          # str  -> dict

    `json` is in the standard library, so nothing to install on macOS / VS Code.
    ```
    The agent never saw turn 1 in *this* call — it pulled "Python", "code-first", and
    "macOS" straight from the **memory store**. That's cross-turn (and cross-session)
    memory."""),

    md("""\
## 🧪 Your turn

1. **Teach it something new.** Make a call that states a fresh preference (*"I've switched
   to type hints everywhere"*), wait a couple of seconds for extraction, then ask a
   follow-up in a new call and confirm the agent honours it.
2. **Prove isolation.** Re-create the agent with `scope="user_sam"` and ask the same
   recommendation question — it should *not* know Dana's preferences.
3. **Go production-style.** Set `scope="{{$userId}}"` in the agent definition (resolved
   from the caller's Entra token) and note how a single agent version serves every user
   with isolated memory.

---

✅ **You created a memory store, wrote and recalled memories, and built an agent that
remembers a user across turns.** Next: coordinate *several* specialised agents.
""" + next_link("07-multi-agent-orchestration", "M7 · Multi-Agent Orchestration")),
]
    # Extra Java cells
    code("""\
// ── 4. Show the full message history stored in the thread ──────────────
System.out.println("=== Full thread history ===");
List<ThreadMessage> history = projectClient.getMessagesClient().listMessages(thread.getId()).stream().toList();
System.out.println("Total messages in thread: " + history.size());
history.forEach(msg -> {
    String text = msg.getContent().stream()
        .filter(c -> "text".equals(c.getType()))
        .map(c -> { MessageTextContent tc = (MessageTextContent) c; return tc.getText().getValue(); })
        .findFirst().orElse("");
    System.out.printf("[%s] %s%n", msg.getRole(), text);
});

projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());"""),


write_notebook(
    "docs/modules/06-agent-memory.ipynb",
    cells,
)
