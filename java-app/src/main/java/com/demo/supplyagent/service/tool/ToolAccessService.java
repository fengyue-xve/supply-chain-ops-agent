package com.demo.supplyagent.service.tool;

import com.demo.supplyagent.security.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * 工具级访问策略(W2-D2)。权限规则唯一来源 = ToolRegistry 中的工具元数据,不另写第二份:
 *  admin 可执行全部工具;viewer(及任何无 admin/viewer 角色的主体,一律按最低权限处理)
 *  只能执行 readOnly=true 的工具。未来敏感写工具(readOnly=false)注册即自动仅 admin 可调,
 *  本类与调用方均无需改动。
 * 角色从 SecurityContext 的 ROLE_* 权限提取,与 JwtAuthFilter 写入的权限一致;
 * 未知角色(如测试的 ROLE_USER)按 viewer 最低权限放行只读工具,写工具仍拒绝。
 */
@Component
public class ToolAccessService {

    /** 403 统一文案:拦截器、AccessDeniedHandler 共用的唯一常量 */
    public static final String ACCESS_DENIED_MESSAGE = "无权限执行该操作";

    private static final String ROLE_PREFIX = "ROLE_";

    /** 纯策略判定(单测友好):admin 全放;其余角色/空/未知一律只放只读工具。 */
    public boolean canAccess(ToolDefinition tool, String role) {
        if (AuthUser.ROLE_ADMIN.equals(role)) {
            return true;
        }
        return tool.isReadOnly();
    }

    /** 运行期判定:从已认证主体解析角色后走纯策略;主体缺失按未授权角色处理(只读语义)。 */
    public boolean canAccess(ToolDefinition tool, Authentication authentication) {
        return canAccess(tool, roleOf(authentication));
    }

    /** 从权限集合解析角色;无 ROLE_admin/ROLE_viewer 返回 null(调用方按最低权限对待)。 */
    public static String roleOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String authorityName = authority.getAuthority();
            if ((ROLE_PREFIX + AuthUser.ROLE_ADMIN).equals(authorityName)) {
                return AuthUser.ROLE_ADMIN;
            }
            if ((ROLE_PREFIX + AuthUser.ROLE_VIEWER).equals(authorityName)) {
                return AuthUser.ROLE_VIEWER;
            }
        }
        return null;
    }
}
