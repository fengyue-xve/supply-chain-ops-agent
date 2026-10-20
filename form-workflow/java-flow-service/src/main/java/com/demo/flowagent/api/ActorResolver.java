package com.demo.flowagent.api;

import com.demo.flowagent.domain.Actor;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.domain.Role;
import com.demo.flowagent.fixtures.FixtureLoader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * 演示鉴权：X-Demo-Token -> fixtures/users.json 中的 (actor_id, role) 服务端映射。
 * token 本身绝不进入审计或响应。
 */
@Component
public class ActorResolver {

    public static final String TOKEN_HEADER = "X-Demo-Token";

    private final FixtureLoader fixtures;

    public ActorResolver(FixtureLoader fixtures) {
        this.fixtures = fixtures;
    }

    public Actor resolve(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            throw new FlowException(ErrorCode.UNAUTHENTICATED, "缺少 " + TOKEN_HEADER + " 请求头", Map.of());
        }
        FixtureLoader.UserEntry user = fixtures.userByToken(token);
        if (user == null) {
            throw new FlowException(ErrorCode.UNAUTHENTICATED, "无法识别的演示 token", Map.of());
        }
        return new Actor(user.actorId(), user.roleEnum());
    }

    /** 解析身份并校验角色；角色不符抛 FORBIDDEN_ROLE（403，零写入）。 */
    public Actor require(HttpServletRequest request, Role... roles) {
        Actor actor = resolve(request);
        if (roles.length > 0 && Arrays.stream(roles).noneMatch(r -> r == actor.role())) {
            throw new FlowException(ErrorCode.FORBIDDEN_ROLE,
                    "角色 " + actor.role() + " 无权执行此操作",
                    Map.of("role", actor.role().name(), "actor_id", actor.actorId()));
        }
        return actor;
    }
}
