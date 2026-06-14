"""Generate Module 13 — Human-in-the-Loop & REST.

Two themes distilled from the upstream reference, simplified to a single Foundry
project + DefaultAzureCredential:

  (a) HUMAN-IN-THE-LOOP — 08-agents/08-08-human-in-the-loop/08-08-01-human-in-the-loop.ipynb
      An agent with a read-only tool (auto-executed) and an irreversible tool
      (`transfer_funds`) that the Responses API returns as a `function_call`
      *without executing*. The caller intercepts approval-required tools, gets a
      human decision, then submits `function_call_output` back via
      `previous_response_id`.

  (b) RAW REST — 08-agents/08-09-invoke-agent-via-rest/{01-single-shot, 02-multi-turn,
      03-streaming}. The exact wire contract the OpenAI SDK assembles: POST to
      `{endpoint}/openai/v1/responses`, bearer token from the
      `https://ai.azure.com/.default` audience, body = `input` + `agent_reference`.
      Multi-turn chains with `previous_response_id`; streaming sets `stream: true`
      and parses Server-Sent Events.

The reference routes through an APIM connection (`{connection}/{model}`) on the
Alpha team spoke; here the model is reachable directly with a plain deployment name.
We invoke the *same* agent over REST that we built for the HITL demo — one project,
one agent, two invocation surfaces.
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M13 · Human-in-the-Loop & REST

> **Goal:** pause an agent for **human approval** before a risky tool call — then learn to invoke that same agent over **raw REST** (single-shot, multi-turn, streaming).
> **You'll use:** `FunctionTool`, the Responses API approval pattern, and `requests` against `/openai/v1/responses`.

---

This lab has **two themes**. First, **human-in-the-loop (HITL)**: when an agent wants to
call a tool that's irreversible — moving money, deleting data — you don't want it firing
unattended. Foundry's Responses API makes this natural: it returns the tool call as an
output item **without executing it**, so *your* code can route it to a human first.

Then we drop below the SDK to the **raw REST** surface. Every `responses.create(...)` call
you've made is just an HTTPS POST with a bearer token — we'll reproduce it with `requests`,
chain turns with `previous_response_id`, and stream tokens over Server-Sent Events.

![Anatomy of a Foundry agent](../../assets/agent-anatomy.png)

!!! note "Sections 1–3 are HITL · sections 4–6 are REST"
    The two halves share one agent: you build a payments agent with an approval-gated tool
    in §1–3, then invoke that exact agent over HTTP in §4–6. The versioned-agent API is
    **preview** — pin `azure-ai-projects` in `pyproject.toml` if a symbol drifts."""),

    # ───────────────────────────── THEME A: HITL ─────────────────────────────
    md("""\
## 1. Configure & build the client

The canonical bootstrap. We also name the agent up front — we'll reference it by **name**
both through the SDK (here) and over REST (later)."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module13HumanInTheLoopAndRest
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module13HumanInTheLoopAndRest.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project    : https://<account>.services.ai.azure.com/api/projects/<project>
    Model      : gpt-4.1-mini
    Agent name : payments-approval-agent
    ```"""),

    md("""\
## 2. Define tools — and create the agent

Two `FunctionTool` schemas. `get_account_balance` is read-only and safe to auto-run;
`transfer_funds` is irreversible. The **`APPROVAL_REQUIRED_TOOLS`** set is the convention
that decides which calls get intercepted — it's *our* policy, not something the model
enforces. We also keep mock implementations so the demo runs end-to-end."""),
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
import java.util.List;
import java.util.Scanner;"""),
    md("""\
!!! note "Expected output"
    ```
    Agent 'payments-approval-agent' ready (version 1).
    Approval-required: {'transfer_funds'}
    ```
    The agent *advertises* both tools to the model. Whether a call actually executes is a
    decision **your** loop makes next — that's the whole point of HITL."""),

    md("""\
## 3. The approval loop — approve & reject

Here's the pattern. Call `responses.create()`, then scan `response.output` for
`function_call` items. Auto-execute safe tools; for approval-required tools, ask a human.
Submit every result back as a `function_call_output` via `previous_response_id` and loop
until no tool calls remain. We pass the human decision as an **`approve` callback** so the
cell stays runnable — in production that callback is a UI prompt, webhook, or queue."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    >>> APPROVE path
    [APPROVED] transfer_funds({'from_account': 'ACC-001', 'to_account': 'ACC-002', 'amount': 500}) -> Transferred $500.00 from ACC-001 to ACC-002.
    The transfer of $500.00 from ACC-001 to ACC-002 is complete.

    >>> REJECT path
    [REJECTED] transfer_funds will not execute
    I wasn't able to complete that transfer — it was rejected by the operator.
    ```
    On approve, the tool runs and the agent confirms; on reject, the rejection string is
    fed back as the tool result, so the agent gracefully reports the decline.

!!! tip "Where the human really lives"
    Swap the `approve` callback for whatever fits your app — a blocking
    `input("Approve? (y/n): ")` in a CLI, a Teams Adaptive Card, or an async approval queue.
    The Responses API holds the run open via `previous_response_id`; nothing executes until
    you submit the `function_call_output`."""),

    # ───────────────────────────── THEME B: REST ─────────────────────────────
    md("""\
## 4. Drop to raw REST — single-shot

Same agent, no SDK. Every `responses.create(...)` is an HTTPS **POST** to
`{endpoint}/openai/v1/responses` with a **bearer token** for the `https://ai.azure.com/.default`
audience — the exact scope the SDK uses internally. The body is just the `input` plus the
`agent_reference` (as a top-level key over the wire, where the SDK put it in `extra_body`)."""),
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
    HTTP   : 200
    Resp id: resp_01J8X...
    Status : completed
    Output : Account ACC-001 has a balance of $5,000.00.
    ```
    `agent_reference.name` resolves to the agent's **latest** version; add
    `"version": "1"` to pin one. The agent auto-ran the read-only `get_account_balance`
    tool server-side — REST callers see only the final text."""),

    md("""\
## 5. Multi-turn over REST — `previous_response_id`

To continue a conversation you **don't** resend history. Capture the first response's `id`
and pass it as `previous_response_id` on the next POST — the service rehydrates the prior
state server-side. Same field, same semantics as the SDK; here it's just another JSON key."""),
    code("""\
// ── 1. Define a "send_email" tool that requires approval ───────────────
FunctionToolDefinition sendEmailTool = new FunctionToolDefinition(
    new FunctionDefinition("send_email", BinaryData.fromString(\"\"\"
        {
          "type": "object",
          "properties": {
            "to": {"type": "string", "description": "Recipient email address"},
            "subject": {"type": "string", "description": "Email subject"},
            "body": {"type": "string", "description": "Email body"}
          },
          "required": ["to", "subject", "body"]
        }
        \"\"\"))
        .setDescription("Send an email to a recipient. Requires human approval.")
);

FunctionToolDefinition getInfoTool = new FunctionToolDefinition(
    new FunctionDefinition("get_info", BinaryData.fromString(\"\"\"
        {
          "type": "object",
          "properties": {
            "topic": {"type": "string", "description": "Topic to get information about"}
          },
          "required": ["topic"]
        }
        \"\"\"))
        .setDescription("Get information about a topic. Does NOT require approval.")
);"""),
    md("""\
!!! note "Expected output"
    ```
    Turn 1: Mira drifted past Saturn's rings, humming a lullaby to the dark.
    Turn 2: A reply hummed back — and Mira realised the dark had been listening.
    ```
    Turn 2 carried no copy of turn 1's text, yet the agent continued the thread — the server
    held the history, keyed by `previous_response_id`. This is the same primitive the HITL
    loop in §3 used to submit `function_call_output` back into an open run."""),

    md("""\
## 6. Streaming over REST — Server-Sent Events

For token-by-token UIs, add **`"stream": true`**. The response content type flips from
`application/json` to `text/event-stream`: a sequence of `data: {json}` lines. You dispatch
on each event's `type` and accumulate **`response.output_text.delta`** chunks as they land."""),
    code("""\
// ── 2. Create the agent ───────────────────────────────────────────────
PersistentAgent agent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
    new CreateAgentOptions(config.chatModel)
        .setName("hitl-agent")
        .setInstructions("You are a helpful assistant. Use tools when appropriate. " +
            "For send_email, always call the tool (do not refuse).")
        .setTools(List.of(sendEmailTool, getInfoTool))
);

System.out.println("PersistentAgent created: " + agent.getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    The story prints **incrementally** as deltas arrive, then the tallies:
    ```
    content-type: text/event-stream

    Every night the keeper lit the lamp against the fog. One storm, a small boat
    followed it home. By dawn, the keeper had a new friend and a story worth telling.

    Chars   : 218
    Events  : {'response.created': 1, 'response.output_item.added': 1,
               'response.output_text.delta': 47, 'response.output_text.done': 1,
               'response.completed': 1}
    ```
    The concatenated `delta` chunks equal the `output_text` you aggregated in §4 — streaming
    just hands it to you a few tokens at a time.

!!! warning "Tokens are short-lived"
    `credential.get_token(...)` returns a token that expires (~60–90 min). For a
    long-running service, fetch a fresh token per request (or cache until near expiry)
    rather than reusing the one captured in §4."""),

    md("""\
## 🧪 Your turn

1. **Add a second gated tool.** Give the agent a `close_account` tool, add it to
   `APPROVAL_REQUIRED_TOOLS`, and confirm `run_with_hitl` intercepts it too. Ask the agent to
   *"close ACC-003"* and reject it.
2. **Pin a version over REST.** Re-version the agent (edit its instructions, call
   `create_version` again), then add `"version": "1"` to the REST `agent_reference` and prove
   the **older** behaviour still answers.
3. **Count streaming events.** Re-run §6 with a longer prompt and compare the
   `response.output_text.delta` count — more text means more deltas, but still **one**
   `response.completed`.

---

✅ **You gated a risky tool behind human approval, then invoked the same agent over raw REST —
single-shot, multi-turn, and streaming.** Next: shrink a big model into a smaller, cheaper one
that mimics it.
""" + next_link("14-fine-tuning-distillation", "M14 · Fine-Tuning & Distillation")),
]
    # Extra Java cells
    code("""\
// ── 3. Run with human-in-the-loop approval ────────────────────────────
System.out.println("=== Human-in-the-loop demo ===");
String userRequest = "Please get info about Azure AI Foundry, then send a summary " +
    "to team@example.com with subject 'Foundry Summary'.";

String reply = runWithApproval(projectClient, agent, userRequest);
System.out.println("Final response: " + reply);

projectClient.getPersistentAgentsAdministrationClient().deleteAgent(agent.getId());"""),


write_notebook(
    "docs/modules/13-human-in-the-loop-and-rest.ipynb",
    cells,
)
