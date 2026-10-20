package com.demo.supplyagent.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器(挂在 SecurityFilterChain,置于 UsernamePasswordAuthenticationFilter 之前)。
 * 异常处理约定(不依赖位于后方的 ExceptionTranslationFilter 兜底):
 *  ① 白名单(GET /health、POST /api/auth/login)直接放行,不做任何 JWT 解析;
 *  ② 无 Authorization 头:继续过滤链,由授权阶段拒绝匿名访问并触发配置的 EntryPoint;
 *  ③ 有头但非 Bearer/空 Bearer/验签失败/过期/claims 非法:
 *     在本 filter 内捕获,clearContext 后主动调用 RestAuthenticationEntryPoint.commence
 *     写 HTTP 401 JSON,随即 return,不再放行;
 *  ④ 校验通过:认证对象(AuthUser + ROLE_xxx)入 SecurityContext 后放行。
 * 本类不打印密码/完整 token/secret。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RestAuthenticationEntryPoint entryPoint;

    public JwtAuthFilter(JwtService jwtService, RestAuthenticationEntryPoint entryPoint) {
        this.jwtService = jwtService;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityConfig.isAnonymousWhitelisted(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            writeInvalidToken(response, request);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            writeInvalidToken(response, request);
            return;
        }

        try {
            AuthUser authUser = jwtService.parse(token);
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            authUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + authUser.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtAuthException e) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, e);
        }
    }

    private void writeInvalidToken(HttpServletResponse response, HttpServletRequest request) throws IOException {
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response,
                new JwtAuthException(JwtAuthException.Reason.INVALID, "token 无效"));
    }
}
