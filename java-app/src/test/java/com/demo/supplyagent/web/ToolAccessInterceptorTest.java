package com.demo.supplyagent.web;

import com.demo.supplyagent.security.AuthUser;
import com.demo.supplyagent.service.tool.ToolAccessService;
import com.demo.supplyagent.service.tool.ToolDefinition;
import com.demo.supplyagent.service.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2-D2 工具入口拦截器单测(mock ToolRegistry):
 * 未注册工具放行(404 语义,不误判 403);viewer 拒绝未来写工具抛 ApiException(403);
 * admin 全放;只读工具 viewer 放行;GET 发现端点不经注册表。
 */
class ToolAccessInterceptorTest {

    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolAccessService accessService = new ToolAccessService();
    private final ToolAccessInterceptor interceptor = new ToolAccessInterceptor(registry, accessService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ToolDefinition def(String name, boolean readOnly) {
        return new ToolDefinition(name, "测试", readOnly, !readOnly, 3_000, "1",
                Map.of("type", "object"), Map.of("type", "object"));
    }

    private MockHttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    private void loginAs(String role) {
        AuthUser user = new AuthUser("u-" + role, role);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void unregisteredToolNamePassesThroughFor404Semantics() {
        when(registry.findByName("no-such-tool")).thenReturn(Optional.empty());
        assertThat(interceptor.preHandle(
                post("/api/agent/tools/no-such-tool"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void viewerDeniedOnFutureWriteToolThrowsApiException403() {
        when(registry.findByName(eq("create-purchase-order")))
                .thenReturn(Optional.of(def("create-purchase-order", false)));
        loginAs(AuthUser.ROLE_VIEWER);
        ApiException ex = catchThrowableOfType(() -> interceptor.preHandle(
                post("/api/agent/tools/create-purchase-order"), new MockHttpServletResponse(), new Object()),
                ApiException.class);
        assertThat(ex).isNotNull().hasMessage(ToolAccessService.ACCESS_DENIED_MESSAGE);
        assertThat(ex.getCode()).isEqualTo(403);
    }

    @Test
    void adminAllowedOnFutureWriteTool() {
        when(registry.findByName(eq("confirm-po")))
                .thenReturn(Optional.of(def("confirm-po", false)));
        loginAs(AuthUser.ROLE_ADMIN);
        assertThat(interceptor.preHandle(
                post("/api/agent/tools/confirm-po"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void viewerAllowedOnRegisteredReadOnlyTool() {
        when(registry.findByName(eq("query-stock-level")))
                .thenReturn(Optional.of(def("query-stock-level", true)));
        loginAs(AuthUser.ROLE_VIEWER);
        assertThat(interceptor.preHandle(
                post("/api/agent/tools/query-stock-level"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void getDiscoveryRequestsSkipToolAclWithoutHittingRegistry() {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/agent/tools/schema");
        assertThat(interceptor.preHandle(get, new MockHttpServletResponse(), new Object())).isTrue();
        verify(registry, never()).findByName(any());
    }
}
