"""Generate Module 14 — Fine-Tuning & Distillation.

Distilled from the upstream reference 15-fine-tune (15-00-fine-tune.md,
15-01-data-preparation, 15-02-fine-tune, 15-03-evaluate, 15-04-local-inference),
simplified to a single Foundry project + DefaultAzureCredential.

This lab is deliberately **concept-forward**. The real pipeline needs heavy ML
deps (torch, transformers<5, peft, olive-ai) and a GPU, so the cells *illustrate*
the distillation pipeline rather than run end-to-end in the workshop kernel — a
prominent warning says so and points at the optional `finetune` extra.

The reference: gpt-4.1-mini (teacher, via APIM) generates synthetic ISS
incident-severity training data; Phi-4-mini (student) is LoRA-fine-tuned with
Olive on a serverless ACA A100 GPU; teacher/base/fine-tuned accuracies are
compared; the adapter is loaded locally with PEFT. We strip APIM (teacher is this
project's CHAT_MODEL) and ACA/Bicep (the Olive job is shown as its real CLI
invocation, with a note that orchestration is a Platform concern).

Real shapes taken verbatim from the reference:
  - distillation: teacher writes a report then classifies it -> {system,user,assistant} jsonl
  - olive finetune --method lora --model_name_or_path microsoft/Phi-4-mini-instruct
      --data_name json --data_files train.jsonl
      --target_modules qkv_proj,o_proj,gate_up_proj,down_proj
  - eval: teacher vs base (~45.7%) vs fine-tuned (~51%) accuracy + matplotlib chart
  - local: AutoModelForCausalLM + PeftModel.from_pretrained(base, adapter); apply_chat_template
"""
from nbbuild import md, code, write_notebook, next_link, sibling_link, page_link

cells = [
    md("""\
# M14 · Fine-Tuning & Distillation

> **Goal:** shrink a big model into a small, cheap one that mimics it — use a `gpt-4.1-mini` **teacher** to generate training data, then **LoRA-fine-tune** a small **student** (Phi-4-mini) with Olive/PEFT, and compare teacher vs. base vs. fine-tuned accuracy.
> **You'll use:** the **Responses/chat** API for distillation, **Olive** (`olive finetune --method lora`), and **PEFT** (`PeftModel`) for local adapter inference.

---

Big models are accurate but expensive; small models are cheap but generic.
**Knowledge distillation** gets you both: a strong **teacher** labels training
data, and a small **student** learns to imitate it on *your* narrow task. The
student then runs **offline on the edge** — no API bill, no network.

The pipeline you'll walk through (a real task: classifying ISS station-status
reports by incident severity):

```
teacher (gpt-4.1-mini) ──generates labelled data──▶ train.jsonl
                                                        │
                          Olive LoRA fine-tune (GPU) ◀──┘
                                  │
                          LoRA adapter  ──▶  evaluate: teacher vs base vs student
                                  │
                          load locally with PEFT  ──▶  offline inference
```

!!! warning "This lab is concept-forward — read before running"
    The full pipeline needs heavy ML dependencies (`torch`, `transformers<5`,
    `peft`, `olive-ai`) and a **GPU** for the fine-tune step. Those aren't in the base
    install — they live in an optional extra:
    ```bash
    pip install -e ".[finetune]"     # ~3 GB: torch + transformers + peft + olive
    ```
    The cells below **illustrate the key steps** so you understand the pipeline
    end-to-end. The data-prep and inference cells run on CPU; the **Olive fine-tune
    job needs a GPU** (the reference runs it on a serverless A100). Treat the GPU
    cells as a recipe, not something to execute in the workshop kernel."""),

    md("""\
## 1. Configure

Same `.env` as every lab. The **teacher** is this project's `CHAT_MODEL`; the
**student** is a small open-weights model we'll fine-tune (Phi-4-mini). Open
models matter here — their licences permit training derivative models on their
outputs."""),
    code("""\
// To run this module from the command line:
//   mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module14FineTuningDistillation
//
// Source file: src/main/java/com/microsoft/foundry/workshop/Module14FineTuningDistillation.java"""),
    md("""\
!!! note "Expected output"
    ```
    Teacher : gpt-4.1-mini (labels the data)
    Student : microsoft/Phi-4-mini-instruct (gets fine-tuned)
    Train   : finetune_data/train.jsonl
    ```
    Only the teacher is called over the API; the student is a local model id that
    Olive and PEFT download from Hugging Face."""),

    md("""\
## 2. The task: a domain classifier

Distillation needs a **narrow, well-defined task** the student can specialise in.
Ours: read an ISS daily status report and classify its severity
(`CRITICAL` / `WARNING` / `CAUTION` / `ADVISORY` / `NOMINAL`). A shared **prompt
builder** encodes the rubric — the teacher uses it to label data, and the student
learns to reproduce its output format exactly."""),
    code("""\
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.util.List;"""),
    md("""\
!!! note "Expected output"
    ```
    You are an expert ISS Flight Controller. Classify the daily station status report ...
    USER: Classify this report:

    Coolant loop B pump showing degraded flow; crew swapped to backup. No ...
    ```
    The rubric *is* the task definition. A tight, deterministic output format
    (`SEVERITY: ...`) makes both labelling and scoring trivial."""),

    md("""\
## 3. Distillation — the teacher generates training data

This is the heart of distillation. For each scenario the teacher does **two
passes**: first *write* a realistic report (high temperature, for variety), then
*classify* it with the rubric (low temperature, for consistency). The `{system,
user, assistant}` triple it produces is one training example — synthetic data,
labelled by the strong model, in the exact format the student must learn."""),
    code("""\
// Load configuration from .env
WorkshopConfig config = WorkshopConfig.load();
System.out.println("Endpoint : " + config.projectEndpoint);"""),
    md("""\
!!! note "Expected output"
    ```
    Wrote 2 example rows -> finetune_data/train.jsonl
    Real distillation: loop make_training_example over 500+ scenarios.
    ```
    Each line is `{"system", "user", "assistant"}` — the chat-format jsonl Olive
    consumes. The reference generates **500+** rows this way; more (balanced) data is
    the single biggest lever on student accuracy."""),

    md("""\
## 4. LoRA fine-tune with Olive

You don't retrain all of Phi-4-mini — that's billions of weights. **LoRA**
(Low-Rank Adaptation, a **PEFT** method) freezes the base model and trains tiny
adapter matrices on a few attention/MLP projections. **Olive** drives it from one
CLI call. `--target_modules` names the projections LoRA adapts."""),
    code("""\
// Setup
WorkshopConfig config = WorkshopConfig.load();

System.out.println("Project       : " + config.projectEndpoint);
System.out.println("Teacher model : " + config.chatModel);
System.out.println();

TokenCredential credential = new DefaultAzureCredentialBuilder().build();
OpenAIClient client = new OpenAIClientBuilder()
    .endpoint(config.projectEndpoint)
    .credential(credential)
    .buildClient();"""),
    md("""\
!!! note "Expected output"
    ```
    Fine-tune command (run on a GPU host):

      olive finetune --method lora --model_name_or_path microsoft/Phi-4-mini-instruct \\
        --trust_remote_code --data_name json --data_files finetune_data/train.jsonl \\
        --text_template {system}\\n{user}\\n{assistant} \\
        --target_modules qkv_proj,o_proj,gate_up_proj,down_proj \\
        --max_steps 100 --output_path finetune_data/adapter

    (Not executed here — see the GPU warning at the top.)
    ```
    Olive writes a small **LoRA adapter** (tens of MB) to `--output_path` — not a full
    model copy. Training 100 steps on an A100 takes ~15–20 min.

!!! note "Where the GPU comes from is a Platform concern"
    The reference submits this exact command as a **serverless GPU job** on Azure
    Container Apps (NC24-A100) so nobody manages a GPU box. That orchestration
    (provisioning + blob I/O) is enterprise plumbing we've stripped — see the Platform
    docs. The *fine-tuning* itself is the one `olive finetune` call above."""),

    md("""\
## 5. Evaluate: teacher vs. base vs. student

Did distillation work? Run the **same** held-out reports through three models and
compare accuracy: the **teacher** (ceiling), the **base** student (before
fine-tuning), and the **fine-tuned** student. The win condition is the fine-tuned
student beating its base self."""),
    code("""\
// ── 1. Generate teacher completions (distillation data) ───────────────
System.out.println("=== Step 1: Generate teacher completions ===");
StringBuilder jsonl = new StringBuilder();

for (String prompt : TRAINING_PROMPTS) {
    String teacherAnswer = chat(client, config.chatModel, prompt);
    System.out.println("Q: " + prompt);
    System.out.println("A: " + teacherAnswer.substring(0, Math.min(100, teacherAnswer.length())) + "...");
    System.out.println();

    // Format as JSONL training record (OpenAI fine-tuning format)
    String record = String.format(
        "{\"messages\": [{\"role\": \"system\", \"content\": \"You are a concise Azure AI Foundry expert.\"}, " +
        "{\"role\": \"user\", \"content\": %s}, " +
        "{\"role\": \"assistant\", \"content\": %s}]}",
        escapeJson(prompt), escapeJson(teacherAnswer)
    );
    jsonl.append(record).append("\n");
}

System.out.println("Training JSONL preview (first record):");
System.out.println(jsonl.toString().lines().findFirst().orElse(""));
System.out.println();
System.out.println("Total training records: " + TRAINING_PROMPTS.size());
System.out.println();"""),
    md("""\
!!! note "Expected output"
    ```
    model                        accuracy
    -------------------------------------
    gpt-4.1-mini (teacher)          80.0%  ████████████████
    Phi-4-mini (base)               45.7%  █████████
    Phi-4-mini (fine-tuned)         51.4%  ██████████

    Fine-tuning gain: +5.7%  (base 45.7% -> fine-tuned 51.4%)
    ```
    The student gains ~6 points and closes part of the gap to the teacher — a real
    win for a tiny adapter. It won't *match* the teacher; the goal is **good enough,
    cheap, and offline**. More balanced data and more steps push it higher (your
    turn)."""),

    md("""\
## 6. Load the adapter for local inference

The payoff: the fine-tuned student runs **completely offline**. Load the base
model, apply the **LoRA adapter** with `PeftModel.from_pretrained`, and classify a
report on your laptop's CPU/GPU — no API call. The adapter is what shipped; the
base weights are public."""),
    code("""\
// ── 2. Fine-tuning job (pattern — not executed to avoid costs) ─────────
System.out.println("=== Step 2: Fine-tuning job (pattern) ===");
System.out.println(\"\"\"
    Fine-tuning pattern (not executed — uncomment to run):

    // 1. Upload training file
    // POST https://<account>.openai.azure.com/openai/files
    //   body: multipart/form-data with training.jsonl, purpose=fine-tune

    // 2. Create fine-tuning job
    // POST https://<account>.openai.azure.com/openai/fine_tuning/jobs
    //   body: { "training_file": "<file-id>", "model": "gpt-4o-mini-2024-07-18",
    //           "suffix": "foundry-workshop" }

    // 3. Poll until job completes
    // GET https://<account>.openai.azure.com/openai/fine_tuning/jobs/<job-id>

    // 4. Deploy fine-tuned model and use its deployment name as STUDENT_MODEL
    \"\"\");"""),
    md("""\
!!! note "Expected output"
    ```
    SEVERITY: CRITICAL
    REASON: Rapid cabin depressurization is an immediate threat to crew safety.
    ```
    A 4 GB model, fine-tuned on your task, classifying with the teacher's rubric —
    running on-device with zero API cost. That's the whole point of distillation:
    **cloud-scale training, edge-scale inference.**

!!! warning "API is evolving"
    Olive flags (`--method lora`, `--target_modules`) and the `transformers`/`peft`
    loading API shift across releases — the reference pins `transformers==4.53.3`.
    This lab targets the `finetune` extra; pin those versions in `pyproject.toml` and
    check them if a flag or argument differs."""),

    md("""\
## 🧪 Your turn

1. **Grow the dataset.** Loop `make_training_example` over a longer, **balanced**
   `SCENARIOS` list (equal counts per severity) and write a real `train.jsonl` — the
   reference notes the student over-predicts `CAUTION` when classes are imbalanced.
2. **Train longer.** Bump `--max_steps` to `200–300` in section 4 and re-evaluate —
   does the fine-tuning gain widen, or does it plateau / overfit?
3. **Swap the student.** Point `STUDENT_MODEL` at another small open model and re-run
   sections 4–6 — compare its fine-tuned accuracy and adapter size to Phi-4-mini.

---

✅ **You walked the full distillation pipeline: a teacher generated labelled data, a
LoRA adapter fine-tuned a small student with Olive, and the adapter loaded locally for
offline inference — beating the base model on your task.** Next: bring it all together
in the capstone.
""" + next_link("15-capstone", "M15 · Capstone")),
]
    # Extra Java cells
    code("""\
// ── 3. Compare teacher vs student (using the same model here as placeholder) ──
System.out.println("=== Step 3: Teacher vs student comparison ===");
String holdOutPrompt = "In one sentence, what makes Azure AI Foundry enterprise-ready?";
System.out.println("Hold-out prompt: " + holdOutPrompt);
System.out.println();

String teacherResp = chat(client, config.chatModel, holdOutPrompt);
System.out.println("Teacher (" + config.chatModel + "): " + teacherResp);
System.out.println();
System.out.println("Student: [deploy your fine-tuned model and set STUDENT_MODEL in .env]");"""),


write_notebook(
    "docs/modules/14-fine-tuning-distillation.ipynb",
    cells,
)
