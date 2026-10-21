package com.demo.supplyagent.gateway;

import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fixed trace routes only; the caller's identity is rechecked by Java at both hops. */
@Component
public class AgentTraceClient {
    private final RestClient client;
    public AgentTraceClient(@Value("${app.agent-gateway.base-url}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public JsonNode request(String id, String authorization, boolean cancel) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,64}")) throw new ApiException(400, "非法请求编号");
        try {
            var request = cancel ? client.post().uri("/agent/traces/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON).body("{}")
                    : client.get().uri("/agent/traces/{id}", id);
            return request.header(HttpHeaders.AUTHORIZATION, authorization).exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) throw new ApiException(404, "运行轨迹不存在、已过期或不属于当前凭证");
                if (status != 200) throw new ApiException(503, "Agent轨迹服务暂不可用");
                return response.bodyTo(JsonNode.class);
            });
        } catch (ApiException error) { throw error; }
        catch (RuntimeException error) { throw new ApiException(503, "Agent轨迹服务暂不可用"); }
    }
}
