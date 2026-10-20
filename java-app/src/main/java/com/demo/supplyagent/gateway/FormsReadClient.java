package com.demo.supplyagent.gateway;

import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Fixed read routes and a read-only upstream identity; never forwards user headers or write actions. */
@Component
public class FormsReadClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RECORD_ID = Pattern.compile("R-[0-9]{4}");
    private static final Pattern RUN_ID = Pattern.compile("run-[0-9a-f]{12}");
    private final URI baseUri;
    private final String readToken;
    private final Duration timeout;
    private final HttpClient client;

    public FormsReadClient(
            @Value("${app.forms-gateway.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${app.forms-gateway.read-token:token-reader-1}") String readToken,
            @Value("${app.forms-gateway.timeout-seconds:2}") int seconds) {
        this.baseUri = URI.create(baseUrl.replaceAll("/+$", ""));
        if (!("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid configured forms service URL");
        }
        this.readToken = readToken;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(seconds, 10)));
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode record(String id) {
        requireId(id, RECORD_ID);
        JsonNode source = read("/api/flow/records/" + id);
        if (!id.equals(source.path("record_id").asText()) || !source.has("business_version")) {
            throw unavailable();
        }
        return select(source, List.of("record_id", "flow_type", "business_version", "sku_code",
                "mark_rule", "outer_box_mark", "updated_at"));
    }

    public JsonNode run(String id) {
        requireId(id, RUN_ID);
        JsonNode source = read("/api/flow/runs/" + id);
        if (!id.equals(source.path("run_id").asText()) || !source.has("status")) {
            throw unavailable();
        }
        return select(source, List.of("run_id", "source_record_id", "flow_type", "business_version",
                "status", "apply_result", "write_count", "replay_count", "failure_reason", "created_at", "updated_at"));
    }

    private static void requireId(String id, Pattern pattern) {
        if (id == null || !pattern.matcher(id).matches()) {
            throw new ApiException(400, "表单记录或任务编号格式不正确");
        }
    }

    private JsonNode read(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri.toString() + path))
                .timeout(timeout).header("Accept", "application/json")
                .header("X-Demo-Token", readToken).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) throw new ApiException(404, "未查询到该表单记录或任务");
            if (response.statusCode() != 200 || response.body().length > 262144) throw unavailable();
            JsonNode result = JSON.readTree(response.body());
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    private static ObjectNode select(JsonNode source, List<String> fields) {
        ObjectNode result = JSON.createObjectNode();
        for (String field : fields) if (source.has(field)) result.set(field, source.get(field));
        return result;
    }

    private static ApiException unavailable() {
        return new ApiException(503, "表单服务暂不可用，请检查服务状态后重试");
    }
}
