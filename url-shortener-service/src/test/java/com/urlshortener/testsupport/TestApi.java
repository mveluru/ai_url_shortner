package com.urlshortener.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** A tiny HTTP client for integration tests. Redirects are NEVER followed so a 302 can be asserted directly. */
public class TestApi {

    private final String base;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    public TestApi(int port) {
        this.base = "http://localhost:" + port;
    }

    public record Response(int status, Map<String, String> headers, String body, ObjectMapper mapper) {
        public JsonNode json() {
            try {
                return mapper.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("Not JSON: " + body, e);
            }
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String code() {
            return json().path("code").asText();
        }
    }

    /** Issues a fresh API key through the dev-only endpoint. */
    public String newApiKey() {
        return send("POST", "/internal/api-keys", null, "{}", Map.of("Content-Type", "application/json")).json().path("apiKey").asText();
    }

    /** Convenience: create an auto-generated short URL for a public host. */
    public Response create(String apiKey, String longUrl) {
        return post("/api/v1/urls", apiKey, Map.of("longUrl", longUrl));
    }

    public Response create(String apiKey, String longUrl, String customAlias) {
        return post("/api/v1/urls", apiKey, Map.of("longUrl", longUrl, "customAlias", customAlias));
    }

    public Response post(String path, String apiKey, Object body) {
        return send("POST", path, apiKey, toJson(body), Map.of("Content-Type", "application/json"));
    }

    public Response get(String path, String apiKey) {
        return send("GET", path, apiKey, null, Map.of());
    }

    public Response get(String path, Map<String, String> headers) {
        return send("GET", path, null, null, headers);
    }

    public Response delete(String path, String apiKey) {
        return send("DELETE", path, apiKey, null, Map.of());
    }

    public Response send(String method, String path, String apiKey, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null) {
                b.header("X-API-Key", apiKey);
            }
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> h = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            return new Response(r.statusCode(), h, r.body(), mapper);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("HTTP call failed: " + method + " " + path, e);
        }
    }

    /**
     * Sends a hand-written request line over a plain socket, for paths the JDK HTTP client refuses to build (invalid
     * percent-encoding, control characters, ...).
     */
    public Response raw(String requestLine, Map<String, String> headers) {
        try (java.net.Socket socket = new java.net.Socket("localhost", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)))) {
            socket.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder(requestLine).append("\r\nHost: localhost\r\nConnection: close\r\n");
            headers.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String all = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String[] head = all.split("\r\n\r\n", 2);
            String[] lines = head[0].split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    h.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
                }
            }
            String body = head.length > 1 ? head[1] : "";
            if (h.getOrDefault("transfer-encoding", "").contains("chunked")) {
                body = dechunk(body);
            }
            return new Response(status, h, body, mapper);
        } catch (IOException e) {
            throw new AssertionError("raw request failed: " + requestLine, e);
        }
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < chunked.length()) {
            int eol = chunked.indexOf("\r\n", pos);
            if (eol < 0) {
                break;
            }
            int size = Integer.parseInt(chunked.substring(pos, eol).trim(), 16);
            if (size == 0) {
                break;
            }
            out.append(chunked, eol + 2, eol + 2 + size);
            pos = eol + 2 + size + 2;
        }
        return out.toString();
    }

    private String toJson(Object body) {
        try {
            return body instanceof String s ? s : mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
