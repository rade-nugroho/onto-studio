package studio.onto.engine;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ContentBlock;
import io.javalin.http.Context;
import org.semanticweb.owlapi.model.OWLEntity;

import java.util.List;
import java.util.Map;

/**
 * Ontology-grounded assistant: answers questions using a deterministic
 * summary of the open ontology (the auto-ontology concept), never free-form
 * guesses about the data.
 */
public final class Assistant {

    private static final String SYSTEM_PROMPT = """
            You are the assistant inside Onto Studio, an ontology workbench.
            Answer only from the ontology context provided. When the context does
            not contain the answer, say so. Keep answers short and factual.
            Axioms are written in DL syntax: subsumption is shown as A sub B,
            existential restriction as A sub exists r.B.
            """;

    private final Workspace workspace;

    public Assistant(Workspace workspace) {
        this.workspace = workspace;
    }

    public void answer(String question, String entityIri, Context ctx) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            ctx.status(503).json(Map.of(
                    "error", "Set ANTHROPIC_API_KEY in the environment to use the assistant"));
            return;
        }
        String context = workspace.ontologyContext(entityIri);
        String prompt = context + "\n\nQuestion: " + question;
        try {
            var client = AnthropicOkHttpClient.fromEnv();
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_5)
                    .maxTokens(1024)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(prompt)
                    .build();
            Message message = client.messages().create(params);
            String answer = message.content().stream()
                    .filter(ContentBlock::isText)
                    .map(block -> block.asText().text())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            ctx.json(Map.of("answer", answer));
        } catch (Exception error) {
            ctx.status(502).json(Map.of("error", "Assistant failed: " + error.getMessage()));
        }
    }
}
