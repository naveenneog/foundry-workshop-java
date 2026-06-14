"""Generate Module 8 — Deep Research.

Distilled from the upstream reference 12-foundry-iq-deep-research
(12-02-deep-research-loop.ipynb; 12-01 deploys the o3 backend — skipped here),
simplified to a single Foundry project + DefaultAzureCredential.

The reference runs two AzureOpenAI clients through an APIM gateway (a research
client for `o3-deep-research` in a separate region, a synthesis client for
`gpt-4.1-mini`) and grounds the loop on a Foundry IQ knowledge base
(`arxiv-nlp-kb`) reached via the Search `retrieve` REST API. We strip all of
that: one `get_openai_client()` serves BOTH model deployments (research +
synthesis), and the "knowledge source" is a tiny in-notebook corpus so the lab
is self-contained and the lesson stays on the **agentic deep-research loop** —
plan, search/fetch, iterate, synthesise a cited report. A note shows how to swap
the corpus for a real Foundry IQ KB (M4) in production.

RESEARCH_MODEL is read from .env (default `o3-deep-research`).
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M8 · Deep Research

> **Goal:** run an **agentic research loop** — a reasoning model that plans, searches a knowledge source, iterates, and returns a **cited synthesis**.
> **You'll use:** `o3-deep-research` over `chat.completions` with function tools, plus a chat model for the final report.

---

A normal chat answer is one shot. **Deep research** is different: you pose a hard question,
and a **reasoning model** (`o3-deep-research`) plans an investigation — it decides what to
**search**, reads what it **fetches**, searches again to fill gaps, and only then concludes.
A second, cheaper model turns those findings into a clean, **cited report**.

The loop you'll build:

```
question → o3-deep-research ──▶ search(query)   ┐
              ▲                  fetch(doc_id)    │  iterate until the model
              └───── tool results ◀──────────────┘  stops calling tools
                                   │
                                   ▼
                         gpt-4.1-mini synthesises a cited report
```

![The inference path](../../assets/inference-path.png)

!!! note "One project, two model roles"
    The reference deploys `o3-deep-research` in a separate region behind an APIM gateway. In
    our single-project setup both deployments live on the **same** project, so one
    `get_openai_client()` serves both. Deep-research models are **preview** and can run for
    minutes — pin `azure-ai-projects` / `openai` in `pyproject.toml` if a shape drifts."""),

    md("""\
## 1. Configure

Two model deployments: the **research** model that does the planning/tool-calling, and the
**synthesis** model that writes the report. `RESEARCH_MODEL` defaults to `o3-deep-research`."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module08DeepResearch
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module08DeepResearch.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project   : https://<account>.services.ai.azure.com/api/projects/<project>
    Research  : o3-deep-research
    Synthesis : gpt-4.1-mini
    ```
    Splitting the roles is deliberate: reasoning models are powerful but slow and pricey, so
    you let one *think* and a cheaper one *write*."""),

    md("""\
## 2. Build the client

The familiar bootstrap. Because a deep-research call can run for **minutes**, we derive a
long-timeout view of the client with `.with_options(...)` for the research loop, and use the
default client for fast synthesis."""),
    code("""\
import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.BingGroundingSearchConfiguration;
import com.azure.ai.agents.persistent.models.BingGroundingSearchToolParameters;
import com.azure.ai.agents.persistent.models.BingGroundingToolDefinition;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.util.List;"""),
    md("""\
!!! note "Expected output"
    ```
    openai_client   : ready
    research_client : ready (timeout=600s)
    ```
    A read-timeout error during research almost always means the default 60s timeout — the
    `with_options(timeout=600.0)` view above is what prevents it."""),

    md("""\
## 3. A knowledge source + two tools

The model can't search the open web here — it researches a **knowledge source** you control.
We use a tiny in-notebook corpus of paper abstracts so the lab is self-contained, and expose
it through two function tools the model will call: **`search`** (find relevant docs) and
**`fetch`** (read one in full)."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Corpus: 4 docs | tools: search, fetch
    ```

!!! tip "Swap in a real knowledge base"
    In production the `search`/`fetch` bodies call a **Foundry IQ knowledge base** instead of
    a dict — the same grounding you built in """ +
    sibling_link("04-grounding-rag-foundry-iq", "M4") + """. Read its endpoint from `.env`
    (`SEARCH_ENDPOINT`) and POST to the KB's `retrieve` API; provisioning the KB is covered
    in the """ + page_link("setup", "Platform docs") + """. The loop below is unchanged."""),

    md("""\
## 4. The deep-research loop

This is the heart of the lab. We hand the **research model** the question + tool schemas,
then loop: each turn the model either **calls tools** (we execute them and feed results back)
or **stops** — signalling it has enough to conclude. We track iterations and tool calls so
the process is observable, and cap the loop for safety."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Project        : " + config.projectEndpoint);
System.out.println("Research model : " + config.researchModel);
System.out.println("Bing connection: " + BING_CONNECTION);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    run_deep_research() ready
    ```
    The loop ends when the model returns a message with **no `tool_calls`** — that's o3
    signalling "I've gathered enough." The `MAX_ITERATIONS` cap is your guardrail against a
    model that keeps searching forever."""),

    md("""\
## 5. Run a question → synthesise a cited report

Pose a real question, run the loop, then hand the model's findings to the **synthesis model**
to format a clean report. Splitting *research* from *writing* keeps the expensive reasoning
focused and lets a fast model do the prose."""),
    code("""\
// ── 1. Create the research agent with Bing grounding ──────────────────
BingGroundingToolDefinition bingTool = new BingGroundingToolDefinition(
    new BingGroundingSearchToolParameters(
        List.of(new BingGroundingSearchConfiguration(BING_CONNECTION))
    )
);

PersistentAgent researchAgent = projectClient.getPersistentAgentsAdministrationClient().createAgent(
    new CreateAgentOptions(config.researchModel)
        .setName("deep-research-agent")
        .setInstructions(
            "You are a thorough research assistant. When asked a question:\n" +
            "1. Search the web for relevant, up-to-date information.\n" +
            "2. Synthesise findings across multiple sources.\n" +
            "3. Cite every factual claim with its source URL.\n" +
            "4. Summarise key findings in a structured format."
        )
        .setTools(List.of(bingTool))
);

System.out.println("Research agent created: " + researchAgent.getName());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    Iteration 1
       search('few-shot learning approaches') -> 3 hit(s)
    Iteration 2
       fetch('doc-001')
       fetch('doc-002')
       fetch('doc-003')
    Iteration 3

    Iterations : 3
    Tool calls : ['search', 'fetch', 'fetch', 'fetch']

    ## Few-Shot Learning Approaches in the Corpus

    The corpus describes three distinct families:

    - **Metric-based** — Prototypical Networks classify by distance to per-class
      prototypes [doc-001], while Matching Networks use attention over a support set
      and introduced episodic training [doc-003].
    - **Optimization-based** — MAML learns an initialization that adapts in a few
      gradient steps, at the cost of second-order gradients [doc-002].

    Metric methods are simpler and cheaper; MAML is model-agnostic but costlier...
    ```
    Notice the **shape**: search → fetch the promising hits → conclude → cited report. The
    model planned the investigation; you only supplied the tools."""),

    md("""\
## 6. Respect the knowledge boundary

A trustworthy researcher admits what it *doesn't* know. Ask something the corpus can't
cover and watch the model **decline** rather than hallucinate — the system prompt told it to
say so explicitly when the corpus falls short."""),
    code("""\
// ── 2. Run a deep research query ──────────────────────────────────────
String researchQuestion =
    "What are the latest developments in AI agent frameworks in 2024–2025? " +
    "Focus on Microsoft's approach vs. other major players. " +
    "Provide a structured summary with citations.";

System.out.println("Research question: " + researchQuestion);
System.out.println();
System.out.println("Running deep research (this may take 30–120 seconds)...");
System.out.println();

String report = runResearch(projectClient, researchAgent, researchQuestion);
System.out.println(report);

projectClient.getPersistentAgentsAdministrationClient().deleteAgent(researchAgent.getId());"""),
    md("""\
!!! note "Expected output"
    ```
    Iteration 1
       search('nuclear fusion energy breakthroughs') -> 0 hit(s)
    Iteration 2

    Iterations : 2
    Tool calls : ['search']

    The corpus does not contain any documents on nuclear fusion energy — it covers
    few-shot learning and transformer efficiency in NLP. I can't answer this from the
    available knowledge source.
    ```

!!! tip "Grounding beats guessing"
    The empty `search` result is the signal: with nothing to fetch, a well-prompted research
    model reports the **boundary** instead of inventing citations. This honesty is exactly
    what you'll measure in """ + sibling_link("09-evaluation", "M9 · Evaluation") + """."""),

    md("""\
## 🧪 Your turn

1. **Add a document.** Drop a new `doc-005` about *cross-lingual transfer* into `CORPUS`, then
   ask a multilingual-NLP question — confirm the loop searches, fetches it, and cites it.
2. **Watch it iterate.** Ask a comparison that spans two topics (*"Contrast few-shot metric
   methods with efficient attention"*) and print `research['tool_calls']` — you should see
   **multiple** search/fetch rounds.
3. **Tune the cap.** Lower `MAX_ITERATIONS` to `1` and observe the loop stop early with a
   thinner report; raise it and watch the model dig deeper. This is the cost/quality dial.

---

✅ **You ran an agentic deep-research loop — plan, search, fetch, iterate — and turned its
findings into a cited report, with the model honouring its knowledge boundary.** Next: measure
answer quality, groundedness, and safety systematically.
""" + next_link("09-evaluation", "M9 · Evaluation")),
]

write_notebook(
    "docs/modules/08-deep-research.ipynb",
    cells,
)
