package com.demo.supplyagent.service.tool;

import com.demo.supplyagent.security.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2-D2 工具访问策略纯单测(无 Spring、无 DB)。
 * 覆盖:admin 全放;viewer 只放只读;未来敏感写工具(readOnly=false)注册后自动仅 admin;
 * 未知/空角色按最低权限;Authentication → 角色解析。
 */
class ToolAccessServiceTest {

    private final ToolAccessService service = new ToolAccessService();

    private ToolDefinition def(boolean readOnly) {
        return new ToolDefinition("tool-" + readOnly, "测试工具", readOnly, !readOnly, 3_000, "1",
                Map.of("type", "object"), Map.of("type", "object"));
    }

    private Authentication auth(String role) {
        AuthUser user = new AuthUser("u-" + role, role);
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void adminCanAccessReadOnlyTool() {
        assertThat(service.canAccess(def(true), AuthUser.ROLE_ADMIN)).isTrue();
    }

    @Test
    void adminCanAccessFutureWriteTool() {
        assertThat(service.canAccess(def(false), AuthUser.ROLE_ADMIN)).isTrue();
    }

    @Test
    void viewerCanAccessReadOnlyTool() {
        assertThat(service.canAccess(def(true), AuthUser.ROLE_VIEWER)).isTrue();
    }

    @Test
    void viewerRejectedOnFutureWriteTool() {
        assertThat(service.canAccess(def(false), AuthUser.ROLE_VIEWER)).isFalse();
    }

    @Test
    void nullOrUnknownRoleTreatedAsLeastPrivilege() {
        assertThat(service.canAccess(def(true), (String) null)).isTrue();
        assertThat(service.canAccess(def(false), (String) null)).isFalse();
        assertThat(service.canAccess(def(true), "user")).isTrue();
        assertThat(service.canAccess(def(false), "user")).isFalse();
    }

    @Test
    void canAccessResolvesRoleFromAuthentication() {
        assertThat(service.canAccess(def(false), auth(AuthUser.ROLE_ADMIN))).isTrue();
        assertThat(service.canAccess(def(false), auth(AuthUser.ROLE_VIEWER))).isFalse();
        assertThat(service.canAccess(def(true), auth(AuthUser.ROLE_VIEWER))).isTrue();
    }

    @Test
    void roleOfMapsAuthoritiesToRoles() {
        assertThat(ToolAccessService.roleOf(auth(AuthUser.ROLE_ADMIN))).isEqualTo(AuthUser.ROLE_ADMIN);
        assertThat(ToolAccessService.roleOf(auth(AuthUser.ROLE_VIEWER))).isEqualTo(AuthUser.ROLE_VIEWER);
        Authentication unknown = UsernamePasswordAuthenticationToken.authenticated(
                "someone", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(ToolAccessService.roleOf(unknown)).isNull();
        assertThat(ToolAccessService.roleOf(null)).isNull();
    }

    @Test
    void denyMessageMatchesContract() {
        assertThat(ToolAccessService.ACCESS_DENIED_MESSAGE).isEqualTo("无权限执行该操作");
    }
}
