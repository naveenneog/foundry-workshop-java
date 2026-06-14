# Setup — do this before the workshop

This takes about **15–20 minutes**. You'll install the toolchain, create a Foundry
project with a couple of model deployments, and run a smoke test that proves your
environment can reach Foundry.

!!! info "Two kinds of dependency"
    - To **read or build this site**, you only need the docs toolchain (`pip install mkdocs-material mkdocs-jupyter`).
    - To **run the labs** against Azure, you also need JDK 17, Maven, and a Foundry project. Both are covered below.

---

## 1. Azure prerequisites

You need an Azure subscription and these tools:

- **Azure CLI** v2.60+ — [install](https://learn.microsoft.com/cli/azure/install-azure-cli)
- **`cognitiveservices` CLI extension** — `az extension add -n cognitiveservices`
- Signed in: **`az login`**

### Create a Foundry project + deploy models

In the **Microsoft Foundry (new)** portal:

1. **Create a project** (this also creates its Foundry account). Note its **project
   endpoint** — it looks like
   `https://<account>.services.ai.azure.com/api/projects/<project>`.
2. In **Build → Models**, deploy:
   - a chat model — **`gpt-4.1-mini`** (used everywhere),
   - an embeddings model — **`text-embedding-3-large`** (M4, M7),
   - *(optional)* a reasoning model — **`o4-mini`** (M1 notes, M13),
   - *(optional)* **`o3-deep-research`** (M8).
3. Give your signed-in identity the **Azure AI Developer** role on the project so
   `DefaultAzureCredential` can call it.

!!! tip "Don't have a project yet? You can still follow along"
    Every lab shows its **Expected output** in prose, so you can read the whole
    workshop without Azure. Provision the project when you're ready to run code.

---

## 2. Java prerequisites

Install **JDK 17+** and **Maven 3.8+**:

=== "Linux / macOS (SDKMAN)"

    ```bash
    curl -s "https://get.sdkman.io" | bash
    sdk install java 17-tem
    sdk install maven
    ```

=== "Homebrew (macOS)"

    ```bash
    brew install openjdk@17 maven
    ```

=== "Windows (winget)"

    ```powershell
    winget install Microsoft.OpenJDK.17
    winget install Apache.Maven
    ```

Verify:

```bash
java -version   # should print 17.x.x
mvn -version    # should print Apache Maven 3.8+
```

---

## 3. Get the code & build

```bash
git clone https://github.com/naveenneog/foundry-workshop-java.git
cd foundry-workshop-java

# Compile
mvn compile

# Run offline unit tests (no Azure required)
mvn test
```

A `BUILD SUCCESS` from `mvn test` with 40 tests passing means your Java toolchain is working.

---

## 4. Install IJava Jupyter kernel (optional — for notebook labs)

If you want to run the labs as Jupyter notebooks, install
[IJava](https://github.com/SpencerPark/IJava):

```bash
# 1. Download the latest IJava release (adjust version as needed)
curl -LO https://github.com/SpencerPark/IJava/releases/download/v1.3.0/ijava-1.3.0.zip
unzip ijava-1.3.0.zip -d ijava

# 2. Install (requires Python + Jupyter)
pip install jupyter
python ijava/install.py --sys-prefix

# 3. Verify the kernel is registered
jupyter kernelspec list   # should show 'java'
```

When you open a lab notebook, select the **Java (IJava/1.3)** kernel.

---

## 5. Configure your environment

Copy the template and fill in your values. **Every lab reads these exact variable
names** via `WorkshopConfig.load()`:

```bash
cp .env.example .env     # then edit .env
```

```ini title=".env"
# Required for all labs
AZURE_SUBSCRIPTION_ID=<your-subscription-id>
PROJECT_ENDPOINT=https://<account>.services.ai.azure.com/api/projects/<project>
CHAT_MODEL=gpt-4.1-mini
EMBEDDING_MODEL=text-embedding-3-large

# Optional — only needed by specific labs
REASONING_MODEL=o4-mini                                   # M1 notes, M13
RESEARCH_MODEL=o3-deep-research                           # M8
SEARCH_ENDPOINT=https://<search>.search.windows.net       # M4, M7
APP_INSIGHTS_CONN_STRING=<application-insights-conn-str>  # M10
```

Authentication is **`DefaultAzureCredential`** throughout — your `az login` identity.
**No model keys live in source code.**

!!! danger "Never commit `.env`"
    `.env` is git-ignored. Keep your subscription id, endpoints, and any keys out of
    version control.

---

## 6. Smoke test

Confirm your environment can compile and reach your project:

```bash
# Compile (resolves all Maven dependencies)
mvn compile

# Run M1 — First Inference (requires .env with PROJECT_ENDPOINT)
mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module01FirstInference
```

**Expected output** (abbreviated):

```
=== Chat completion ===
Azure AI Foundry is Microsoft's unified platform for building enterprise AI...

=== Embedding ===
Embedding vector length: 3072

=== Streaming ===
Streaming: Azure  AI  Foundry  provides ...
```

If you see output like that, you're ready to run all 15 labs.

A `401`/`403` means your identity lacks the **Azure AI Developer** role; a
`DefaultAzureCredentialException` usually means you need `az login`.

---

You're ready. → Read the **[Concepts](concepts.md)**, then start
**[M1 · First inference](modules/01-first-inference.ipynb)**.
