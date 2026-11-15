import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes {@code GET /health} on this container's loopback. Sends
 * {@code X-Engine-Token} from {@code ENGINE_TOKEN} when that variable is set.
 * Does not turn authentication off.
 */
public final class Healthcheck {

    private Healthcheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv("ENGINE_PORT");
        if (port == null || port.isBlank()) {
            port = "7717";
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port.trim() + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET();
        String token = System.getenv("ENGINE_TOKEN");
        if (token != null && !token.isBlank()) {
            request.header("X-Engine-Token", token);
        }
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                System.err.println("health status " + response.statusCode());
                System.exit(1);
            }
        } catch (Exception error) {
            System.err.println("healthcheck failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
