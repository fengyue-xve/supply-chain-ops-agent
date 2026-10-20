package com.demo.supplyagent.security;

import com.demo.supplyagent.service.tool.ToolAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2-D2 403 出口信封单测(无 Spring):HTTP 403 + {code:403, message:无权限执行该操作, data:null}。
 */
class RestAccessDeniedHandlerTest {

    private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler(new ObjectMapper());

    @Test
    void writesForbiddenEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest(),
                response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(body.get("message").asText()).isEqualTo(ToolAccessService.ACCESS_DENIED_MESSAGE);
        assertThat(body.get("data").isNull()).isTrue();
    }
}
