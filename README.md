# Microsoft Foundry: End-to-End Workshop (Java)

A hands-on, end-to-end coding workshop for building enterprise AI agents and apps
on **Microsoft Foundry (Azure AI Foundry)** — Azure's unified PaaS for models,
agents, knowledge, evaluation, and observability.

This workshop is implemented in **Java 17** using Maven and the official Azure Java
SDKs, including `azure-ai-agents-persistent`, `azure-ai-openai`, and `azure-ai-projects`.

📖 **Read the workshop online:** <https://monuminu.github.io/foundry-workshop/>

The workshop is a MkDocs site whose lab pages are runnable Jupyter notebooks
(using the [IJava](https://github.com/SpencerPark/IJava) kernel).
Read it on the web, or clone this repo and run every lab against your own Foundry
project.

## What you'll build

Starting from a single Foundry project and `az login`, you progress from your first
model call to a grounded, tool-using, evaluated, observable agent:

1. **First inference** — chat, embeddings, streaming, the Responses API
2. **Your first agent** — versioned prompt agents
3. **Tools & function calling** — Code Interpreter + custom tools
4. **Grounding / RAG** — Azure AI Search knowledge bases (Foundry IQ)
5. **MCP tools** — connect an agent to a Model Context Protocol server
6. **Agent memory** — cross-turn context
7. **Multi-agent orchestration** — router + specialists
8. **Deep research** — agentic research loops with cited synthesis
9. **Evaluation** — quality, agent, and custom evaluators
10. **Observability** — OpenTelemetry tracing + continuous evaluation
11. **Guardrails** — Prompt Shields, PII, custom blocklists
12. **Red teaming** — automated adversarial scans
13. **Human-in-the-loop & REST** — approvals + raw REST invocation
14. **Fine-tuning** — knowledge distillation to a small model
15. **Capstone** — combine it all, plus where to go next

## Prerequisites

- **JDK 17+** — [download](https://adoptium.net/)
- **Maven 3.8+** — [download](https://maven.apache.org/download.cgi)
- **Azure CLI** v2.60+ — [install](https://learn.microsoft.com/cli/azure/install-azure-cli)
- Signed in: **`az login`**
- A Foundry project with deployed models (see [Setup](docs/setup.md))

Optional (for running labs as Jupyter notebooks):

- **Python 3.10+** with `jupyter` installed
- **IJava kernel** — [install](https://github.com/SpencerPark/IJava#install)

## Quick start

```bash
git clone https://github.com/naveenneog/foundry-workshop-java.git
cd foundry-workshop-java

# Copy environment template and fill in your project details
cp .env.example .env   # edit PROJECT_ENDPOINT, CHAT_MODEL, etc.

# Compile the project
mvn compile

# Run all offline unit tests
mvn test

# Run a specific module (e.g. M1 · First Inference)
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module01FirstInference

# Preview the docs site (requires Python + pip install mkdocs-material mkdocs-jupyter)
mkdocs serve        # http://127.0.0.1:8000
```

## Running individual modules

Each module is a standalone `main()` class. Run any of the 15 modules with:

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module<NN><ClassName>
```

Examples:

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module01FirstInference
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module02YourFirstAgent
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module15Capstone
```

## Project structure

```
src/
  main/java/com/microsoft/foundry/workshop/
    WorkshopConfig.java          ← shared .env loader
    Module01FirstInference.java  ← M1–M15 lab source files
    ...
    Module15Capstone.java
  test/java/com/microsoft/foundry/workshop/
    WorkshopConfigTest.java
    Module03ToolsAndFunctionCallingTest.java
    Module09EvaluationTest.java
    Module11GuardrailsTest.java
    Module12RedTeamingTest.java
scripts/
  nbbuild.py                     ← notebook authoring helpers (IJava kernel)
  gen_01_first_inference.py      ← per-module notebook generators
  ...
docs/
  modules/                       ← generated .ipynb lab notebooks
pom.xml                          ← Maven project, Azure SDK BOM 1.2.32
```

## Key dependencies

| Artifact | Version | Purpose |
|---|---|---|
| `azure-ai-agents-persistent` | 1.0.0-beta.2 | Agents API (PersistentAgentsClient) |
| `azure-ai-openai` | 1.0.0-beta.16 | Chat, embeddings, streaming |
| `azure-ai-projects` | 1.0.0-beta.2 | Connections, datasets, indexes |
| `azure-identity` | (via BOM) | DefaultAzureCredential |
| `azure-monitor-opentelemetry-exporter` | 1.0.0-beta.28 | Distributed tracing |

## Authoring notebooks

Lab notebooks are generated programmatically — never hand-edited as JSON. Each
module has a generator under `scripts/gen_<id>.py` that builds its notebook via
`scripts/nbbuild.py`. To regenerate:

```bash
PYTHONPATH=scripts python scripts/gen_01_first_inference.py
mkdocs build --strict                                  # whole-site build
```

The notebooks use the **IJava** Jupyter kernel (`kernel_name="java"`). Install IJava
before running cells:

```bash
# Download IJava release jar and install
# See: https://github.com/SpencerPark/IJava#install
```

## Acknowledgements

The module arc and code patterns are distilled from the excellent
[corticalstack/awesome-foundry-nextgen](https://github.com/corticalstack/awesome-foundry-nextgen)
enterprise lab series, adapted here to Java with a single-project,
`DefaultAzureCredential` setup for a one-/multi-day coding workshop.

## License

MIT
