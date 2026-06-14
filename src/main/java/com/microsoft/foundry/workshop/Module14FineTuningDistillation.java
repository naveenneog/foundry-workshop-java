package com.microsoft.foundry.workshop;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.List;

/**
 * M14 · Fine-Tuning &amp; Distillation
 *
 * <p>Goal: use Azure OpenAI's fine-tuning API to distil knowledge from a large
 * "teacher" model into a smaller, cheaper "student" model, then compare outputs.
 *
 * <p>Fine-tuning in Azure OpenAI works by uploading a JSONL training file and
 * submitting a fine-tuning job via the REST API or SDK.
 *
 * <p>This module demonstrates:
 * <ol>
 *   <li>Generate synthetic training data (teacher completions for student prompts).</li>
 *   <li>Format training data as JSONL for fine-tuning.</li>
 *   <li>Show the fine-tuning API call pattern (commented out to avoid incurring costs).</li>
 *   <li>Compare teacher vs student responses on held-out prompts.</li>
 * </ol>
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=com.microsoft.foundry.workshop.Module14FineTuningDistillation}
 *
 * <p>Note: The Azure OpenAI fine-tuning Java SDK is in preview. The actual fine-tuning
 * job submission uses the Azure OpenAI REST API directly.
 */
public class Module14FineTuningDistillation {

    /** Training prompts — the student will learn to answer these about Foundry. */
    private static final List<String> TRAINING_PROMPTS = List.of(
        "What is Azure AI Foundry in one sentence?",
        "What does DefaultAzureCredential do?",
        "How do agents use tools in Foundry?",
        "What is the purpose of embeddings?",
        "What is the Responses API?"
    );

    public static void main(String[] args) {
        WorkshopConfig config = WorkshopConfig.load();

        System.out.println("Project       : " + config.projectEndpoint);
        System.out.println("Teacher model : " + config.chatModel);
        System.out.println();

        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        OpenAIClient client = new OpenAIClientBuilder()
            .endpoint(config.projectEndpoint)
            .credential(credential)
            .buildClient();

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
        System.out.println();

        // ── 2. Fine-tuning job (pattern — not executed to avoid costs) ─────────
        System.out.println("=== Step 2: Fine-tuning job (pattern) ===");
        System.out.println("""
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
            """);

        // ── 3. Compare teacher vs student (using the same model here as placeholder) ──
        System.out.println("=== Step 3: Teacher vs student comparison ===");
        String holdOutPrompt = "In one sentence, what makes Azure AI Foundry enterprise-ready?";
        System.out.println("Hold-out prompt: " + holdOutPrompt);
        System.out.println();

        String teacherResp = chat(client, config.chatModel, holdOutPrompt);
        System.out.println("Teacher (" + config.chatModel + "): " + teacherResp);
        System.out.println();
        System.out.println("Student: [deploy your fine-tuned model and set STUDENT_MODEL in .env]");
    }

    private static String chat(OpenAIClient client, String model, String userMessage) {
        ChatCompletions resp = client.getChatCompletions(model,
            new ChatCompletionsOptions(List.of(
                new ChatRequestSystemMessage("You are a concise Azure AI Foundry expert."),
                new ChatRequestUserMessage(userMessage)
            ))
        );
        return resp.getChoices().get(0).getMessage().getContent();
    }

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
