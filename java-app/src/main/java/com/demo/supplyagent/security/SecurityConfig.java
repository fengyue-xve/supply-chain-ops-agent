package com.demo.supplyagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 安全装配(匿名面最小化):
 * 白名单仅 GET /health + POST /api/auth/login;其余请求一律要求认证;
 * 无状态 session;认证失败统一 JSON(见 RestAuthenticationEntryPoint)。
 * 注意:isAnonymousWhitelisted 与 authorizeHttpRequests 的白名单两处为同一口径,
 * 改动必须同步(前者让 JwtAuthFilter 短路,后者做最终授权放行)。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    public static final String HEALTH_PATH = "/health";
    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String AUDIT_HISTORY_PATH = "/api/audit/history";
    public static final String PO_PENDING_PATH = "/api/po/pending";

    public static boolean isAnonymousWhitelisted(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (HEALTH_PATH.equals(uri)) {
            return HttpMethod.GET.matches(request.getMethod());
        }
        return LOGIN_PATH.equals(uri) && HttpMethod.POST.matches(request.getMethod());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtService jwtService(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService,
                                       RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        return new JwtAuthFilter(jwtService, restAuthenticationEntryPoint);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                                                   RestAccessDeniedHandler restAccessDeniedHandler,
                                                   JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        // W2-D2:已认证无权限(未来 URL 级拒绝等)统一 403 信封;当前工具级 ACL 在 MVC 拦截器
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, HEALTH_PATH).permitAll()
                        .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll()
                        // W3-④ 审计时间线仅 admin(匿名→401 走 entryPoint;viewer→403 走 accessDeniedHandler);
                        // 必须排在 anyRequest().authenticated() 之前,JwtAuthFilter 写入 ROLE_admin 与 hasRole("admin") 对应
                        .requestMatchers(HttpMethod.POST, AUDIT_HISTORY_PATH).hasRole("admin")
                        // W4 待确认采购单仅 admin;同上必须排在 anyRequest().authenticated() 之前
                        .requestMatchers(HttpMethod.POST, PO_PENDING_PATH).hasRole("admin")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
