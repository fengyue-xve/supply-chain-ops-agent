package com.demo.supplyagent.gateway;

import com.demo.supplyagent.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentTraceClientTest {
    @Test void fixedPathsForwardAuthorizationAndCancelWithPost() throws Exception {
        var observed=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/agent/traces",exchange -> {
            observed.set(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="{\"code\":0,\"data\":{\"status\":\"ok\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var client=new AgentTraceClient("http://127.0.0.1:"+server.getAddress().getPort());
            assertEquals("ok",client.request("run-a","Bearer synthetic",false).path("data").path("status").asText());
            assertEquals("GET /agent/traces/run-a Bearer synthetic",observed.get());
            client.request("run-a","Bearer synthetic",true);
            assertEquals("POST /agent/traces/run-a/cancel Bearer synthetic",observed.get());
        } finally { server.stop(0); }
    }
    @Test void invalidIdsNeverMakeAnHttpRequest() {
        var client=new AgentTraceClient("http://127.0.0.1:1");
        assertEquals(400,assertThrows(ApiException.class,()->client.request("../../secret","Bearer synthetic",false)).getCode());
    }
    @Test void unavailableServiceIs503RatherThanLoginFailure() {
        var client=new AgentTraceClient("http://127.0.0.1:1");
        assertEquals(503,assertThrows(ApiException.class,()->client.request("run-a","Bearer synthetic",false)).getCode());
    }
}
