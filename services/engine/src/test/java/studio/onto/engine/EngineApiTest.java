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

    private static final String LANG = "http://protege.stanford.edu/plugins/owl/protege#defaultLanguage";

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

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
    void createsOntologyAndAppliesBatchWithAnnotations(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("new.owl");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String created = body(client.post("/ontology/new",
                    "{\"path\":\"" + file + "\"}", HEADERS));
            assertTrue(created.contains("\"axiomCount\":0"), created);

            String batch = body(client.post("/axiom/batch",
                    "{\"text\":\"Class: Customer\\n  Annotations: rdfs:comment \\\"A person who orders\\\""
                            + "\\n  SubClassOf: Thing\\nClass: Order\\n  SubClassOf: Thing\"}", HEADERS));
            assertTrue(batch.contains("added"), batch);

            String customerIri = URLEncoder.encode("file:" + file + "#Customer", StandardCharsets.UTF_8);
            String entity = body(client.get("/entity?iri=" + customerIri, HEADERS));
            assertTrue(entity.contains("comment: A person who orders"), entity);
            assertTrue(entity.contains("\"label\":\"Customer\""), entity);
        });
    }

    @Test
    void extractsPlainTextDocuments(@TempDir Path tempDir) throws Exception {
        Path doc = tempDir.resolve("prd.md");
        Files.writeString(doc, "# PRD\nCoffee shop ordering system");
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String extracted = body(client.post("/document/extract",
                    "{\"path\":\"" + doc + "\"}", HEADERS));
            assertTrue(extracted.contains("Coffee shop ordering system"), extracted);
            assertTrue(extracted.contains("\"chars\":"), extracted);
        });
    }

    @Test
    void catalogEntriesAreSignedAndInstalled(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".onto-studio"));
        Files.writeString(home.resolve(".onto-studio.env"), "ONTO_STUDIO_CATALOG_KEY=test-key\n");
        byte[] bytes = "catalog jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                String added = body(client.post("/plugins/catalog/add",
                        "{\"name\":\"sample\",\"url\":\"" + url + "\",\"sha256\":\"" + digest + "\"}",
                        HEADERS));
                assertTrue(added.contains("signature"), added);

                String catalog = body(client.get("/plugins/catalog", HEADERS));
                assertTrue(catalog.contains("\"valid\":true"), catalog);

                String installed = body(client.post("/plugins/catalog/install",
                        "{\"name\":\"sample\"}", HEADERS));
                assertTrue(installed.contains("installed"), installed);
            });

            Path catalogFile = home.resolve(".onto-studio/plugins/catalog.json");
            Files.writeString(catalogFile, "{\"sample\":{\"url\":\"" + url
                    + "\",\"sha256\":\"" + digest + "\",\"signature\":\"deadbeef\"}}");

            JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
                String rejected = body(client.post("/plugins/catalog/install",
                        "{\"name\":\"sample\"}", HEADERS));
                assertTrue(rejected.contains("signature is invalid"), rejected);
            });
        } finally {
            System.setProperty("user.home", previousHome);
            http.stop(0);
        }
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
        String priorKey = System.getProperty(Assistant.API_KEY_PROPERTY);
        String priorDisable = System.getProperty("studio.onto.disableEnvFile");
        System.setProperty(Assistant.API_KEY_PROPERTY, "");
        System.setProperty("studio.onto.disableEnvFile", "true");
        try {
            JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
                open(client);
                try (okhttp3.Response response = client.post("/assistant", "{\"question\":\"hi\"}", HEADERS)) {
                    assertEquals(503, response.code());
                    String body = body(response);
                    assertTrue(body.contains("OPENROUTER_API_KEY"), body);
                    assertFalse(body.contains("ANTHROPIC"), body);
                }
            });
        } finally {
            restoreProperty(Assistant.API_KEY_PROPERTY, priorKey);
            restoreProperty("studio.onto.disableEnvFile", priorDisable);
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

    @Test
    void newOntologyInitializesActionsMarkingsAndBranches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("fresh.owl");
        String classIri = "file:" + file + "#Widget";
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            assertEquals(200, client.post("/ontology/new", "{\"path\":\"" + file + "\"}", HEADERS).code());
            assertEquals(200, client.post("/axiom/batch",
                    "{\"text\":\"Class: Widget\\n  SubClassOf: Thing\"}", HEADERS).code());

            okhttp3.Response action = client.post("/action/commit",
                    "{\"action\":\"set-label\",\"params\":{\"iri\":\"" + classIri
                            + "\",\"label\":\"Main Widget\"},\"reason\":\"init\"}", HEADERS);
            assertEquals(200, action.code(), body(action));

            okhttp3.Response mark = client.post("/marking/set",
                    "{\"iri\":\"" + classIri + "\",\"marking\":\"restricted\"}", HEADERS);
            assertEquals(200, mark.code(), body(mark));
            assertTrue(body(client.get("/marking?iri=" + URLEncoder.encode(classIri, StandardCharsets.UTF_8), HEADERS))
                    .contains("restricted"));

            assertEquals(200, client.post("/branch/create", "{\"name\":\"dev\"}", HEADERS).code());
            String branches = body(client.get("/branch/list", HEADERS));
            assertTrue(branches.contains("\"main\"") && branches.contains("\"dev\""), branches);
            assertEquals(200, client.post("/branch/switch", "{\"name\":\"dev\"}", HEADERS).code());
            assertEquals(200, client.post("/branch/promote", "{\"name\":\"dev\"}", HEADERS).code());
        });
    }

    @Test
    void installPluginRejectsNonHttpSchemesAndSanitizesQueryAndTraversal(@TempDir Path home) throws Exception {
        byte[] bytes = "jar bytes".getBytes(StandardCharsets.UTF_8);
        String digest = sha256Hex(bytes);
        com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        http.start();
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
                okhttp3.Response fileScheme = client.post("/plugins/install",
                        "{\"url\":\"file:///etc/passwd\",\"sha256\":\"" + digest + "\"}", HEADERS);
                assertEquals(400, fileScheme.code());
                String fileBody = body(fileScheme);
                assertTrue(fileBody.contains("http and https"), fileBody);

                String queryUrl = "http://127.0.0.1:" + http.getAddress().getPort()
                        + "/good.jar?token=abc&v=1";
                okhttp3.Response queryInstall = client.post("/plugins/install",
                        "{\"url\":\"" + queryUrl + "\",\"sha256\":\"" + digest + "\"}", HEADERS);
                assertEquals(200, queryInstall.code(), body(queryInstall));
                assertTrue(Files.exists(home.resolve(".onto-studio/plugins/good.jar")));

                String traversalUrl = "http://127.0.0.1:" + http.getAddress().getPort()
                        + "/..%2Fevil.jar";
                okhttp3.Response traversalInstall = client.post("/plugins/install",
                        "{\"url\":\"" + traversalUrl + "\",\"sha256\":\"" + digest + "\"}", HEADERS);
                assertEquals(200, traversalInstall.code(), body(traversalInstall));
                assertFalse(Files.exists(home.resolve(".onto-studio/evil.jar")));
                assertTrue(Files.exists(home.resolve(".onto-studio/plugins/evil.jar")));
            });
        } finally {
            System.setProperty("user.home", previousHome);
            http.stop(0);
        }
    }

    @Test
    void invalidInputsReturn400InsteadOf500() {
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            open(client);
            assertEquals(400, client.get("/graph?depth=notanumber", HEADERS).code());
            assertEquals(400, client.post("/axiom/subclass", "null", HEADERS).code());
            assertEquals(400, client.post("/axiom/subclass", "{}", HEADERS).code());
            assertEquals(400, client.post("/axiom/subclass",
                    "{\"sub\":\"not a valid iri\",\"sup\":\"" + NS + "Pizza\"}", HEADERS).code());
            assertEquals(400, client.post("/ontology/open", "null", HEADERS).code());
            assertEquals(400, client.post("/document/extract", "{}", HEADERS).code());
        });
    }

    @Test
    void corsAllowsLocalOriginsAndRejectsExternalOrigins() {
        assertTrue(Main.isAllowedOrigin("http://localhost:5173"));
        assertTrue(Main.isAllowedOrigin("http://127.0.0.1:4173"));
        assertTrue(Main.isAllowedOrigin("http://[::1]:5173"));
        assertTrue(Main.isAllowedOrigin("tauri://localhost"));
        assertTrue(Main.isAllowedOrigin("https://tauri.localhost"));
        assertFalse(Main.isAllowedOrigin("https://evil.example"));
        assertFalse(Main.isAllowedOrigin("http://localhost.evil.example"));

        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            okhttp3.Response local = client.get("/health", builder -> builder
                    .header("X-Engine-Token", "test")
                    .header("Origin", "http://localhost:5173"));
            assertEquals(200, local.code());
            assertEquals("http://localhost:5173", local.header("Access-Control-Allow-Origin"));

            okhttp3.Response evil = client.get("/health", builder -> builder
                    .header("X-Engine-Token", "test")
                    .header("Origin", "https://evil.example"));
            assertEquals(200, evil.code());
            assertEquals(null, evil.header("Access-Control-Allow-Origin"));

            assertEquals(401, client.get("/health", builder -> builder
                    .header("X-Engine-Token", "wrong")).code());
        });
    }

    @Test
    void someValuesFromExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            okhttp3.Response bad = client.post("/action/preview",
                    subclassExpr("IceCream", "{\"kind\":\"nope\"}"), HEADERS);
            String badBody = body(bad);
            assertEquals(400, bad.code(), badBody);
            assertTrue(badBody.contains("Unknown expression kind"), badBody);

            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", restriction("some", NS + "hasTopping", classFiller(NS + "MeatTopping"))),
                    "∃ hasTopping.MeatTopping");
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", restriction("some", LANG, datatypeFiller(XSD_STRING))),
                    "∃ defaultLanguage.string");
        });
    }

    @Test
    void allValuesFromExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", restriction("only", NS + "hasSpiciness", classFiller(NS + "Mild"))),
                    "∀ hasSpiciness.Mild");
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", restriction("only", LANG, datatypeFiller(XSD_STRING))),
                    "∀ defaultLanguage.string");
        });
    }

    @Test
    void minCardinalityExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("min", 2, NS + "hasTopping", classFiller(NS + "MeatTopping"))),
                    "≥ 2 hasTopping.MeatTopping");
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("min", 2, LANG, datatypeFiller(XSD_STRING))),
                    "≥ 2 defaultLanguage.string");
        });
    }

    @Test
    void maxCardinalityExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("max", 1, NS + "hasBase", null)),
                    "≤ 1 hasBase.⊤");
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("max", 2, LANG, null)),
                    "≤ 2 defaultLanguage.Literal");
        });
    }

    @Test
    void exactCardinalityExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("exactly", 1, NS + "hasBase", classFiller(NS + "PizzaBase"))),
                    "= 1 hasBase.PizzaBase");
            assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                    subclassExpr("IceCream", cardinality("exactly", 1, LANG, datatypeFiller(XSD_INTEGER))),
                    "= 1 defaultLanguage.integer");
        });
    }

    @Test
    void intersectionExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                subclassExpr("IceCream", junction("intersection", classFiller(NS + "DomainConcept"), classFiller(NS + "Pizza"))),
                "DomainConcept ⊓ Pizza"));
    }

    @Test
    void unionExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                subclassExpr("IceCream", junction("union", classFiller(NS + "DomainConcept"), classFiller(NS + "Pizza"))),
                "DomainConcept ⊔ Pizza"));
    }

    @Test
    void complementExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                subclassExpr("IceCream", "{\"kind\":\"complement\",\"operand\":" + classFiller(NS + "DomainConcept") + "}"),
                "¬DomainConcept"));
    }

    @Test
    void namedClassExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "IceCream", "add-subclass-expression",
                subclassExpr("IceCream", classFiller(NS + "Pizza")),
                "IceCream ⊑ Pizza"));
    }

    @Test
    void equivalentClassExpression(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "IceCream", "add-equivalent-class",
                action("add-equivalent-class", "{\"iri\":\"" + NS + "IceCream\",\"expression\":"
                        + restriction("some", NS + "hasTopping", classFiller(NS + "MeatTopping")) + "}"),
                "IceCream ≡ ∃ hasTopping.MeatTopping"));
    }

    @Test
    void functionalPropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> {
            assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                    characteristic(NS + "hasTopping", "functional", ""),
                    "⊤ ⊑ ≤ 1 hasTopping");
            assertStructuredAxiom(client, LANG, "add-property-characteristic",
                    characteristic(LANG, "functional", ""),
                    "⊤ ⊑ ≤ 1 defaultLanguage");
        });
    }

    @Test
    void inverseFunctionalPropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "inverse-functional", ""),
                "⊤ ⊑ ≤ 1 hasTopping⁻"));
    }

    @Test
    void transitivePropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "transitive", ""),
                "hasTopping ∈ R⁺"));
    }

    @Test
    void symmetricPropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "symmetric", ""),
                "hasTopping ≡ hasTopping⁻"));
    }

    @Test
    void asymmetricPropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "asymmetric", ""),
                "hasTopping ⊑ ¬ hasTopping⁻"));
    }

    @Test
    void reflexivePropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "reflexive", ""),
                "⊤ ⊑ ∃ hasTopping .self"));
    }

    @Test
    void irreflexivePropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasTopping", "add-property-characteristic",
                characteristic(NS + "hasTopping", "irreflexive", ""),
                "⊤ ⊑ ¬∃ hasTopping .self"));
    }

    @Test
    void inversePropertyCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasCountryOfOrigin", "add-property-characteristic",
                characteristic(NS + "hasCountryOfOrigin", "inverse",
                        "\"inverse\":\"" + NS + "hasSpiciness\""),
                "hasCountryOfOrigin ≡ hasSpiciness⁻"));
    }

    @Test
    void propertyChainCharacteristic(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "hasCountryOfOrigin", "add-property-characteristic",
                characteristic(NS + "hasCountryOfOrigin", "chain",
                        "\"chain\":[\"" + NS + "hasTopping\",\"" + NS + "hasSpiciness\"]"),
                "hasTopping ∘ hasSpiciness ⊑ hasCountryOfOrigin"));
    }

    @Test
    void objectPropertyAssertion(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "Italy", "add-object-assertion",
                action("add-object-assertion", "{\"iri\":\"" + NS + "Italy\",\"property\":\""
                        + NS + "hasCountryOfOrigin\",\"target\":\"" + NS + "France\"}"),
                "hasCountryOfOrigin(Italy, France)"));
    }

    @Test
    void dataPropertyAssertion(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "Italy", "add-data-assertion",
                action("add-data-assertion", "{\"iri\":\"" + NS + "Italy\",\"property\":\""
                        + LANG + "\",\"value\":\"7\",\"datatype\":\"integer\"}"),
                "defaultLanguage(Italy, 7)"));
    }

    @Test
    void sameIndividualAssertion(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "Italy", "add-same-as",
                action("add-same-as", "{\"iri\":\"" + NS + "Italy\",\"other\":\"" + NS + "France\"}"),
                "France = Italy"));
    }

    @Test
    void differentIndividualsAssertion(@TempDir Path tempDir) throws Exception {
        withPizza(tempDir, client -> assertStructuredAxiom(client, NS + "America", "add-different-from",
                action("add-different-from", "{\"iri\":\"" + NS + "America\",\"other\":\"" + NS + "Italy\"}"),
                "America ≠ Italy"));
    }

    private static void withPizza(Path tempDir, Consumer<HttpClient> check) throws Exception {
        Path copy = tempDir.resolve("pizza.owl");
        Files.copy(Path.of(PIZZA), copy);
        JavalinTest.test(Main.createApp(new Workspace(), "test"), (server, client) -> {
            String opened = body(client.post("/ontology/open",
                    "{\"path\":\"" + copy + "\"}", HEADERS));
            assertTrue(opened.contains("\"axiomCount\":930"), opened);
            check.accept(client);
        });
    }

    private static String action(String name, String params) {
        return "{\"action\":\"" + name + "\",\"params\":" + params + "}";
    }

    private static String subclassExpr(String className, String expression) {
        return action("add-subclass-expression",
                "{\"iri\":\"" + NS + className + "\",\"expression\":" + expression + "}");
    }

    private static String classFiller(String iri) {
        return "{\"kind\":\"class\",\"iri\":\"" + iri + "\"}";
    }

    private static String datatypeFiller(String iri) {
        return "{\"kind\":\"datatype\",\"iri\":\"" + iri + "\"}";
    }

    private static String restriction(String kind, String property, String filler) {
        return "{\"kind\":\"" + kind + "\",\"property\":\"" + property + "\",\"filler\":" + filler + "}";
    }

    private static String cardinality(String kind, int n, String property, String filler) {
        String fillerJson = filler == null ? "" : ",\"filler\":" + filler;
        return "{\"kind\":\"" + kind + "\",\"property\":\"" + property
                + "\",\"cardinality\":" + n + fillerJson + "}";
    }

    private static String junction(String kind, String left, String right) {
        return "{\"kind\":\"" + kind + "\",\"operands\":[" + left + "," + right + "]}";
    }

    private static String characteristic(String iri, String characteristic, String extra) {
        String tail = extra == null || extra.isBlank() ? "" : "," + extra;
        return action("add-property-characteristic",
                "{\"iri\":\"" + iri + "\",\"characteristic\":\"" + characteristic + "\"" + tail + "}");
    }

    private static void assertStructuredAxiom(HttpClient client, String iri, String actionName,
                                              String json, String fragment) {
        okhttp3.Response preview = client.post("/action/preview", json, HEADERS);
        String previewBody = body(preview);
        assertEquals(200, preview.code(), previewBody);
        assertTrue(previewBody.contains("\"action\":\"" + actionName + "\""), previewBody);
        assertTrue(previewBody.contains(fragment), previewBody);

        String query = URLEncoder.encode(iri, StandardCharsets.UTF_8);
        String before = body(client.get("/entity?iri=" + query, HEADERS));
        assertFalse(before.contains(fragment), before);

        okhttp3.Response commit = client.post("/action/commit", json, HEADERS);
        String commitBody = body(commit);
        assertEquals(200, commit.code(), commitBody);
        assertTrue(commitBody.contains("\"canUndo\":true"), commitBody);
        assertTrue(commitBody.contains("\"action\":\"" + actionName + "\""), commitBody);

        String after = body(client.get("/entity?iri=" + query, HEADERS));
        assertTrue(after.contains(fragment), after);

        String evidence = body(client.get("/evidence?iri=" + query, HEADERS));
        assertTrue(evidence.contains("\"actionName\":\"" + actionName + "\""), evidence);
        assertTrue(evidence.contains(fragment), evidence);

        String undo = body(client.post("/undo", "", HEADERS));
        assertTrue(undo.contains("\"canRedo\":true"), undo);
        String undone = body(client.get("/entity?iri=" + query, HEADERS));
        assertFalse(undone.contains(fragment), undone);
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

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static String body(okhttp3.Response response) {
        try {
            return response.body().string();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
