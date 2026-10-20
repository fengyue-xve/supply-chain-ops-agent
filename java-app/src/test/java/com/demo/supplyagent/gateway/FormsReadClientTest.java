package com.demo.supplyagent.gateway;

import com.demo.supplyagent.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormsReadClientTest {
    private HttpServer server;
    private FormsReadClient client;
    private final AtomicInteger hits = new AtomicInteger();
    private int code = 200;
    private String body = "{\"record_id\":\"R-1001\",\"business_version\":3,\"sku_code\":\"AUD-001\",\"internal_token\":\"must-not-return\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("X-Demo-Token")).isEqualTo("read-test");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new FormsReadClient("http://127.0.0.1:" + server.getAddress().getPort(), "read-test", 1);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void readsWithDedicatedIdentityAndFiltersUnknownFields() {
        var data = client.record("R-1001");
        assertThat(data.path("sku_code").asText()).isEqualTo("AUD-001");
        assertThat(data.has("internal_token")).isFalse();
        assertThat(hits).hasValue(1);
    }
    @Test void runUsesItsOwnFixedRoute() {
        body = "{\"run_id\":\"run-123456abcdef\",\"status\":\"WAITING_APPROVAL\",\"write_count\":0}";
        assertThat(client.run("run-123456abcdef").path("write_count").asInt()).isZero();
    }
    @Test void rejectsTraversalAndArbitraryPathsBeforeHttp() {
        assertThatThrownBy(() -> client.record("R-1001/../../approve")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.run("http://other-host/")).isInstanceOf(ApiException.class);
        assertThat(hits).hasValue(0);
    }
    @Test void upstreamAuthenticationDoesNotExpireLocalSession() {
        code = 403;
        assertThatThrownBy(() -> client.record("R-1001")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(503));
    }
    @Test void missingRecordIs404() {
        code = 404;
        assertThatThrownBy(() -> client.record("R-1001")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(404));
    }
    @Test void malformedOrMismatchedBodyIsUnavailable() {
        body = "not-json";
        assertThatThrownBy(() -> client.record("R-1001")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(503));
        body = "{\"record_id\":\"R-9999\",\"business_version\":3}";
        assertThatThrownBy(() -> client.record("R-1001")).isInstanceOf(ApiException.class);
    }
    @Test void outageIsUnavailableInsteadOfEmptySuccess() {
        server.stop(0);
        assertThatThrownBy(() -> client.record("R-1001")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(503));
    }
}
