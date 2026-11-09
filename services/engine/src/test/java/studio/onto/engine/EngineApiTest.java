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
    void pluginsWithoutGrantedPermissionsAreSkipped() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String plugins = body(client.get("/plugins", HEADERS));
            assertTrue(plugins.contains("example"), plugins);
            assertTrue(plugins.contains("\"permissions\""), plugins);
            assertFalse(plugins.contains("writer"), plugins);
        });
    }

    @Test
    void installsPluginOnlyWithMatchingChecksum(@TempDir Path home) throws Exception {
        byte[] bytes = "not really a jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/plugin.jar", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        http.start();
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/plugin.jar";
        String digest = sha256Hex(bytes);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
                String bad = body(client.post("/plugins/install",
                        "{\"url\":\"" + url + "\",\"sha256\":\"deadbeef\"}", HEADERS));
                assertTrue(bad.contains("Checksum mismatch"), bad);

                String good = body(client.post("/plugins/install",
                        "{\"url\":\"" + url + "\",\"sha256\":\"" + digest + "\"}", HEADERS));
                assertTrue(good.contains("installed"), good);
                assertTrue(Files.exists(home.resolve(".onto-studio/plugins/plugin.jar")),
                        "plugin jar should be written to the plugins directory");
            });
        } finally {
            System.setProperty("user.home", previousHome);
            http.stop(0);
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void pluginActionsAreDiscoveredAndApplied(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            String plugins = body(client.get("/plugins", HEADERS));
            assertTrue(plugins.contains("example:deprecate"), plugins);

            okhttp3.Response commitResponse = client.post("/action/commit",
                    "{\"action\":\"example:deprecate\",\"params\":{\"iri\":\"" + NS
                            + "Margherita\"}}", HEADERS);
            String commit = body(commitResponse);
            assertEquals(200, commitResponse.code(), commit);
            assertTrue(commit.contains("add:"), commit);
            String branches = body(client.get("/branch/list", HEADERS));
            assertTrue(branches.contains("\"axioms\":931"), branches);

            String entity = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(entity.toLowerCase().contains("deprecated"), entity);
        });
    }

    @Test
    void validatesAxiomTextWithoutApplyingIt() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            String good = body(client.post("/axiom/validate",
                    "{\"text\":\"Class: Margherita\\n  SubClassOf: Pizza\"}", HEADERS));
            assertTrue(good.contains("\"ok\":true"), good);

            String bad = body(client.post("/axiom/validate",
                    "{\"text\":\"Class: Margherita\\n  SubClassOf: DoesNotExist\"}", HEADERS));
            assertTrue(bad.contains("\"ok\":false"), bad);
            assertTrue(bad.contains("DoesNotExist"), bad);

            String stillOpen = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertFalse(stillOpen.contains("Margherita ⊑ Pizza"), stillOpen);
        });
    }

    @Test
    void propertyAndIndividualActions(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));

            String domain = body(client.post("/action/commit",
                    "{\"action\":\"set-domain\",\"params\":{\"iri\":\"" + NS
                            + "hasTopping\",\"class\":\"" + NS + "Pizza\"}}", HEADERS));
            assertTrue(domain.contains("set domain"), domain);

            String property = body(client.get("/entity?iri=" + encoded("hasTopping"), HEADERS));
            assertTrue(property.contains("\"label\":\"Pizza\""), property);

            String type = body(client.post("/action/commit",
                    "{\"action\":\"add-type\",\"params\":{\"iri\":\"" + NS
                            + "America\",\"class\":\"" + NS + "Country\"}}", HEADERS));
            assertTrue(type.contains("add type"), type);

            String individual = body(client.get("/entity?iri=" + encoded("America"), HEADERS));
            assertTrue(individual.contains("\"types\":[{\""), individual);
        });
    }

    @Test
    void markingsFilterExports(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            body(client.post("/marking/set",
                    "{\"iri\":\"" + NS + "Margherita\",\"marking\":\"restricted\"}", HEADERS));

            String entity = body(client.get("/entity?iri=" + encoded("Margherita"), HEADERS));
            assertTrue(entity.contains("\"marking\":\"restricted\""), entity);
            assertTrue(entity.contains("\"usage\":"), entity);
            assertTrue(entity.contains("\"subClasses\":"), entity);

            Path filtered = tempDir.resolve("filtered.owl");
            String filteredBody = body(client.post("/export",
                    "{\"path\":\"" + filtered + "\"}", HEADERS));
            assertTrue(skippedCount(filteredBody) > 0, filteredBody);
            assertTrue(Files.exists(filtered), "filtered export should exist");

            Path full = tempDir.resolve("full.owl");
            String fullBody = body(client.post("/export",
                    "{\"path\":\"" + full + "\",\"includeRestricted\":true}", HEADERS));
            assertEquals(0, skippedCount(fullBody));
        });
    }

    private static int skippedCount(String json) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"skipped\":(\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    @Test
    void branchesIsolateEditsAndPromote(@TempDir Path tempDir) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            body(client.post("/ontology/open", "{\"path\":\"" + copy + "\"}", HEADERS));
            body(client.post("/branch/create", "{\"name\":\"proposal\"}", HEADERS));
            String switched = body(client.post("/branch/switch", "{\"name\":\"proposal\"}", HEADERS));
            assertTrue(switched.contains("\"branch\":\"proposal\""), switched);

            body(client.post("/axiom/subclass",
                    "{\"sub\":\"" + NS + "Margherita\",\"sup\":\"" + NS + "Pizza\"}", HEADERS));

            String diff = body(client.get("/branch/diff?name=proposal", HEADERS));
            assertTrue(diff.contains("\"added\""), diff);
            assertTrue(diff.contains("SubClassOf"), diff);
            assertTrue(diff.contains("Margherita ⊑ Pizza"), diff);

            body(client.post("/branch/switch", "{\"name\":\"main\"}", HEADERS));
            String mainList = body(client.get("/branch/list", HEADERS));
            assertTrue(mainList.contains("\"axioms\":930"), mainList);

            String promoted = body(client.post("/branch/promote",
                    "{\"name\":\"proposal\",\"reason\":\"reviewed\"}", HEADERS));
            assertTrue(promoted.contains("\"changes\":1"), promoted);

            String after = body(client.get("/branch/list", HEADERS));
            assertTrue(after.contains("\"axioms\":931"), after);
            assertFalse(after.contains("proposal"), after);
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
