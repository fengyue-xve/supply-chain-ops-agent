package com.demo.supplyagent.web;

import com.demo.supplyagent.security.AuthUser;
import com.demo.supplyagent.service.tool.ToolAccessService;
import com.demo.supplyagent.service.tool.ToolDefinition;
import com.demo.supplyagent.service.tool.ToolRegistry;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * 工具入口 ACL 强制点(W2-D2):挂在 POST /api/agent/tools/{name} 的执行入口,
 * Controller 内零 if/else,权限元数据只读自 ToolRegistry。
 *  ① 非 POST(工具发现 GET /tools、/schema)只走既有认证,不做工具级拦截;
 *  ② POST 且工具名未注册:放行交给 MVC 404(未知工具语义不被 ACL 抢成 403);
 *  ③ POST 且工具已注册:按 ToolAccessService 策略判角色,拒绝时抛 ApiException(403)
 *     由 ApiExceptionHandler 统一写 HTTP 403 信封。
 * 位置说明:位于 JwtAuthFilter(认证)之后、Controller 方法之前——已认证才可能到此处。
 */
@Component
public class ToolAccessInterceptor implements HandlerInterceptor {

    private final ToolRegistry toolRegistry;
    private final ToolAccessService toolAccessService;

    public ToolAccessInterceptor(ToolRegistry toolRegistry, ToolAccessService toolAccessService) {
        this.toolRegistry = toolRegistry;
        this.toolAccessService = toolAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String toolName = lastPathSegment(request.getRequestURI());
        if (toolName == null) {
            return true;
        }
        Optional<ToolDefinition> definition = toolRegistry.findByName(toolName);
        if (definition.isEmpty()) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!toolAccessService.canAccess(definition.get(), authentication)) {
            throw new ApiException(403, ToolAccessService.ACCESS_DENIED_MESSAGE);
        }
        return true;
    }

    private String lastPathSegment(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : null;
    }
}
