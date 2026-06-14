"""Generate Module 4 — Grounding / RAG (Foundry IQ).

Distilled from the upstream reference 10-foundry-iq (10-02 index-and-ingest,
10-03 knowledge-base-setup, 10-05 agent-iq-queries), simplified to a single
Foundry project + DefaultAzureCredential. No APIM gateway, no hub/spoke, no
3k-arxiv dataset, no security-trimming — a tiny inline corpus and one minimal
knowledge base keep the focus on the RAG arc and the SDK calls.
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M4 · Grounding / RAG (Foundry IQ)

> **Goal:** stop your agent hallucinating — ground its answers in *your* documents, with **citations**.
> **You'll use:** `azure-search-documents` (vector + semantic index), a **Foundry IQ knowledge base**, and an agent wired to it via the Responses API.

---

Models are confident even when they're wrong. **Grounding** fixes that: you embed
your documents, index them in **Azure AI Search**, wrap that index in a **Foundry IQ
knowledge base (KB)**, and attach the KB to an agent. Now every answer is drawn from —
and cites — *your* corpus.

The arc of this lab is three steps: **embed + index → build a KB → ground an agent.**

![Grounding with Foundry IQ (RAG)](../../assets/rag-foundry-iq.png)

!!! note "Provisioning is conceptual here"
    This lab assumes an **Azure AI Search** service already exists (its endpoint is in
    your `.env` as `SEARCH_ENDPOINT`). Standing up Search and wiring its identity to
    your Foundry account is covered in the Platform docs — here we stay focused on the
    SDK calls. The Foundry IQ KB classes are **preview** and evolving; pin
    `azure-search-documents` in `pyproject.toml` if a method name drifts."""),

    md("""\
## 1. Configure

We read the same project variables as every lab, plus the **Search endpoint** and a
**project connection name** that lets your project reach the KB's tool endpoint."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module04GroundingRagFoundryIq
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module04GroundingRagFoundryIq.java"""),
    md("""\
!!! note "Expected output"
    ```
    Project : https://<account>.services.ai.azure.com/api/projects/<project>
    Search  : https://<search>.search.windows.net
    Index   : foundry-facts
    Embed   : text-embedding-3-large
    ```
    `SEARCH_CONNECTION` is the name of a **RemoteTool** project connection that points
    at the KB — created once, per the Platform docs."""),

    md("""\
## 2. Build the clients

One `DefaultAzureCredential` authenticates *everything* — the Foundry project, the
OpenAI-compatible client (for embeddings), and the two Azure AI Search clients."""),
    code("""\
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.ai.agents.persistent.PersistentAgentsClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.AISearchIndexResource;
import com.azure.ai.agents.persistent.models.AzureAISearchQueryType;
import com.azure.ai.agents.persistent.models.AzureAISearchToolDefinition;
import com.azure.ai.agents.persistent.models.AzureAISearchToolResource;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.RunStatus;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.ToolResources;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.HnswAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Map;"""),
    md("""\
!!! note "Expected output"
    ```
    project_client : ready
    openai_client  : ready
    index_client   : ready
    search_client  : ready
    ```
    A `403` from the Search clients means your identity is missing the **Search Index
    Data Contributor** role on the Search service."""),

    md("""\
## 3. Create the vector + semantic index

The index is the backbone of retrieval. We define a small schema: a key, two text
fields, and a **vector field** for semantic similarity. The HNSW algorithm powers
fast approximate-nearest-neighbour search; a **semantic configuration** adds Microsoft's
re-ranker on top. An **integrated vectorizer** lets the index embed *queries* at search
time using your project's embedding deployment."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Index 'foundry-facts' ready (4 fields)
    ```

!!! tip "Why `stored=False` on the vector?"
    Vectors are only used for the ANN search — you never need them back in results.
    `stored=False` skips persisting them in retrievable form and cuts index storage
    substantially. The integrated vectorizer uses your **managed identity** to call the
    embedding deployment, so no API key lives in the index."""),

    md("""\
## 4. Embed and upload a tiny corpus

Real RAG runs over thousands of docs; to learn the mechanics we use **five short facts
about Microsoft Foundry**. We embed each one with the same `embeddings.create` call from
""" + sibling_link("01-first-inference", "M1") + """, attach the vector, and upload."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

if (config.searchEndpoint.isBlank()) {
    System.err.println("SEARCH_ENDPOINT is not set — please add it to .env");
    System.exit(1);
}

System.out.println("Project : " + config.projectEndpoint);
System.out.println("Search  : " + config.searchEndpoint);
System.out.println("Index   : " + INDEX_NAME);
System.out.println("Embed   : " + config.embeddingModel);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();

OpenAIClient openAIClient = new OpenAIClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();

PersistentAgentsClient projectClient = new PersistentAgentsClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();

SearchIndexClient indexClient = new SearchIndexClientBuilder()
    .endpoint(config.searchEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Uploaded 5 documents to 'foundry-facts'.
    Vector dims: 3072
    ```
    One `embeddings.create` call batches all five docs. In production you'd batch ~100
    at a time with retry/back-off."""),

    md("""\
## 5. Build the Foundry IQ knowledge base

A **knowledge source** registers the index as a named retrieval target; a **knowledge
base** sits on top and is what an agent actually queries. We use **`minimal` reasoning
effort** — pure semantic retrieval with no extra LLM planning pass — which is the
simplest, fastest, lowest-cost option and needs no model config of its own. The agent's
*own* model does the reasoning and citing."""),
    code("""\
// ── 1. Create the search index ─────────────────────────────────────────
System.out.println("=== Creating search index ===");
createSearchIndex(indexClient);"""),
    md("""\
!!! note "Expected output"
    ```
    Knowledge source 'foundry-facts-ks' and knowledge base 'foundry-facts-kb' ready.
    ```

!!! tip "`minimal` vs `low` effort"
    `minimal` does a direct semantic search — great for single-topic lookups. `low`
    (and higher) add an LLM **query-planning** pass that decomposes complex questions
    into sub-queries before searching. Start minimal; raise the effort only when answer
    relevance demands it (it costs a model call per query)."""),

    md("""\
## 6. Retrieve with citations

Before wiring the KB to an agent, query it **directly** with
`KnowledgeBaseRetrievalClient`. This isolates *retrieval* from *agent* config and shows
the cited chunks the agent will reason over. A `minimal`-effort KB takes an **intent**
(a pre-parsed search directive) rather than a chat message, because it has no LLM to
interpret a conversation."""),
    code("""\
// ── 2. Generate embeddings and upload documents ────────────────────────
System.out.println("=== Uploading embedded documents ===");
uploadDocuments(openAIClient, indexClient, config.embeddingModel);"""),
    md("""\
!!! note "Expected output"
    ```
    Retrieved text:
     Foundry IQ grounds an agent by attaching a knowledge base built over an Azure AI
     Search index. The agent retrieves chunks and cites them, so answers are backed ...

    Citations:
      - [3] Knowledge bases
      - [1] Foundry projects
    ```
    The KB returned the most relevant chunk **plus** the document ids/titles to cite.
    `EXTRACTIVE_DATA` means the text is your indexed content, verbatim — no rewriting."""),

    md("""\
## 7. Ground an agent on the KB

Each KB automatically exposes an **MCP endpoint**. We attach it to a versioned agent as
an `MCPTool`, pointed at that endpoint through the project's **RemoteTool connection**
(`SEARCH_CONNECTION`). The agent's instructions force it to retrieve before answering and
to cite — so the final answer is grounded *and* attributable. We then ask it via the
same `responses.create` surface from """ + sibling_link("01-first-inference", "M1") + """."""),
    code("""\
// ── 3. Wire an agent to the search index and ask a grounded question ──
System.out.println("=== Grounded agent query ===");
runGroundedAgent(projectClient, config.chatModel, config.searchEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Agent 'foundry-facts-agent' ready (version 1).
    Tools called: ['knowledge_base']
    A Foundry IQ knowledge base grounds an agent's answers: it retrieves chunks from an
    Azure AI Search index and the agent cites them (Knowledge bases). Authentication uses
    DefaultAzureCredential — your az login identity locally, a managed identity in
    production, with no API keys in code (DefaultAzureCredential).
    ```
    Note the **(parenthetical citations)** — they map straight back to the corpus titles.
    Ask something off-corpus (e.g. *"who won the 1998 World Cup?"*) and the agent returns
    `I don't know.` instead of guessing."""),

    md("""\
## 🧪 Your turn

1. **Add a document.** Append a sixth fact to `corpus`, re-embed and re-upload, then ask
   the agent a question only that doc can answer. Confirm the new title appears as a
   citation.
2. **Raise the reasoning effort.** Swap `KnowledgeRetrievalMinimalReasoningEffort()` for
   `KnowledgeRetrievalLowReasoningEffort()` (this KB variant needs a model config — see
   the note) and ask a *compound* question. Compare which citations come back.
3. **Tighten grounding.** Change the instructions to require **two** citations per claim,
   re-version the agent, and re-run. Watch the answer style change.

---

✅ **You embedded a corpus, indexed it, built a Foundry IQ knowledge base, and grounded
an agent that cites your data.** Next: reach *external* systems through MCP tools.
""" + next_link("05-mcp-tools", "M5 · MCP Tools")),
]

write_notebook(
    "docs/modules/04-grounding-rag-foundry-iq.ipynb",
    cells,
)
