package studio.onto.engine;

import io.javalin.testtools.HttpClient;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineApiTest {

    private static final Consumer<okhttp3.Request.Builder> HEADERS = builder -> builder
            .header("X-Engine-Token", "test")
            .header("Content-Type", "application/json");

    private static final String PIZZA = Path.of("src/test/resources/pizza.owl").toAbsolutePath().toString();

    private static final String NS = "http://example.invalid/ontologies/pizza/2005/10/18/pizza.owl#";

    @Test
    void rejectsRequestsWithoutToken() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(401, client.get("/health").code());
            assertEquals(200, client.get("/health", HEADERS).code());
        });
    }

    @Test
    void opensOntologyAndBrowsesHierarchy() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String hierarchy = body(client.get("/hierarchy", HEADERS));
            assertTrue(hierarchy.contains("DomainConcept"), hierarchy);

            String entity = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(entity.contains("\"kind\":\"class\""), entity);
            assertTrue(entity.contains("PizzaComUmNome"), entity);
        });
    }

    @Test
    void editIsUndoableAndRedoable() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String add = body(client.post("/axiom/subclass",
                    "{\"sub\":\"" + NS + "Margherita\",\"sup\":\"" + NS + "Pizza\"}", HEADERS));
            assertTrue(add.contains("\"canUndo\":true"), add);
            assertTrue(entityAxioms(client).contains("Margherita ⊑ Pizza"), entityAxioms(client));

            String undo = body(client.post("/undo", "", HEADERS));
            assertTrue(undo.contains("\"canRedo\":true"), undo);
            assertFalse(entityAxioms(client).contains("Margherita ⊑ Pizza"));

            body(client.post("/redo", "", HEADERS));
            assertTrue(entityAxioms(client).contains("Margherita ⊑ Pizza"));
        });
    }

    @Test
    void savesEditsAndReloadsThem(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            body(client.post("/axiom/subclass",
                    "{\"sub\":\"" + NS + "Margherita\",\"sup\":\"" + NS + "Pizza\"}", HEADERS));
            String save = body(client.post("/ontology/save", "{}", HEADERS));
            assertTrue(save.contains("\"dirty\":false"), save);

            String reopened = body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            assertTrue(reopened.contains("\"axiomCount\":931"), reopened);
        });
    }

    @Test
    void searchesAcrossEntityKinds() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String results = body(client.get("/search?q=hasTopping", HEADERS));
            assertTrue(results.contains("\"kind\":\"object property\""), results);
        });
    }

    @Test
    void addsAxiomsFromManchesterText() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String response = body(client.post("/axiom/add-text",
                    "{\"text\":\"ObjectProperty: hasTopping\\n  Characteristics: Functional\"}", HEADERS));
            assertTrue(response.contains("≤ 1 hasTopping"), response);

            String entity = body(client.get("/entity?iri=" + encoded("hasTopping"), HEADERS));
            assertTrue(entity.contains("≤ 1 hasTopping"), entity);
        });
    }

    @Test
    void reportsDomainsAndRangesForProperties() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String property = body(client.get("/entity?iri=" + encoded("hasTopping"), HEADERS));
            assertTrue(property.contains("\"kind\":\"object property\""), property);
            assertTrue(property.contains("\"domains\":[{\""), property);
            assertTrue(property.contains("\"ranges\":[{\""), property);

            String individual = body(client.get("/entity?iri=" + encoded("America"), HEADERS));
            assertTrue(individual.contains("\"kind\":\"individual\""), individual);
            assertTrue(individual.contains("\"types\":[{\""), individual);
        });
    }

    @Test
    void runsReasonerAndServesInferredHierarchy() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String started = body(client.post("/reasoner/start", "", HEADERS));
            assertTrue(started.contains("\"running\":true"), started);

            String status = body(client.get("/reasoner/status", HEADERS));
            assertTrue(status.contains("\"running\":true"), status);

            String inferred = body(client.get("/hierarchy?inferred=true", HEADERS));
            assertTrue(inferred.contains("\"inferred\":true"), inferred);
            assertTrue(inferred.contains("DomainConcept"), inferred);

            String stopped = body(client.post("/reasoner/stop", "", HEADERS));
            assertTrue(stopped.contains("\"running\":false"), stopped);
        });
    }

    @Test
    void actionsPreviewThenCommitWithAudit(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));

            String preview = body(client.post("/action/preview",
                    "{\"action\":\"set-label\",\"params\":{\"iri\":\"" + NS
                            + "Margherita\",\"label\":\"Margherita Pizza\"}}", HEADERS));
            assertTrue(preview.contains("remove label"), preview);

            String before = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(before.contains("Margherita"), before);

            String commit = body(client.post("/action/commit",
                    "{\"action\":\"set-label\",\"params\":{\"iri\":\"" + NS
                            + "Margherita\",\"label\":\"Margherita Pizza\"},\"reason\":\"rename for clarity\"}",
                    HEADERS));
            assertTrue(commit.contains("set-label"), commit);

            String after = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(after.contains("Margherita Pizza"), after);

            String audit = body(client.get("/audit", HEADERS));
            assertTrue(audit.contains("rename for clarity"), audit);
            assertTrue(audit.contains("\"actionName\":\"set-label\""), audit);
        });
    }

    @Test
    void recordsEvidenceAndExplainsAxioms(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            body(client.post("/axiom/subclass",
                    "{\"sub\":\"" + NS + "Margherita\",\"sup\":\"" + NS + "Pizza\"}", HEADERS));

            String evidence = body(client.get("/evidence?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(evidence.contains("\"source\":\"manual\""), evidence);
            assertTrue(evidence.contains("\"author\""), evidence);

            String why = body(client.get("/why?iri=" + encoded("Margherita")
                    + "&axiom=" + URLEncoder.encode("Margherita ⊑ Pizza", StandardCharsets.UTF_8), HEADERS));
            assertTrue(why.contains("\"status\":\"asserted\""), why);
            assertTrue(why.contains("\"action\":\"add\""), why);
        });
    }

    @Test
    void assistantNeedsApiKey() {
        System.setProperty("studio.onto.disableEnvFile", "true");
        try {
            JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
                open(client);
                assertEquals(503, client.post("/assistant", "{\"question\":\"hi\"}", HEADERS).code());
            });
        } finally {
            System.clearProperty("studio.onto.disableEnvFile");
        }
    }

    @Test
    void graphReturnsNodesAndEdges() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String graph = body(client.get("/graph?depth=2", HEADERS));
            assertTrue(graph.contains("\"nodes\""), graph);
            assertTrue(graph.contains("\"edges\""), graph);
            assertTrue(graph.contains("DomainConcept"), graph);
        });
    }

    private static void open(HttpClient client) {
        String response = body(client.post("/ontology/open", "{\"path\":\"" + PIZZA + "\"}", HEADERS));
        assertTrue(response.contains("\"axiomCount\":930"), response);
    }

    private static String entityAxioms(HttpClient client) {
        return body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
    }

    private static String encoded(String label) {
        return URLEncoder.encode(NS + label, StandardCharsets.UTF_8);
    }

    private static String body(okhttp3.Response response) {
        try {
            return response.body().string();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
