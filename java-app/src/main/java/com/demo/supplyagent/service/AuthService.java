package com.demo.supplyagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.supplyagent.dto.LoginResponse;
import com.demo.supplyagent.entity.AppUser;
import com.demo.supplyagent.mapper.AppUserMapper;
import com.demo.supplyagent.security.JwtService;
import com.demo.supplyagent.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录编排:查账号 + BCrypt 校验 + JWT 签发。
 * 账号不存在与密码错误返回同一文案「用户名或密码错误」(防账号枚举);
 * 失败统一 ApiException(401) → HTTP 401 + code 401(与安全层 401 信封一致)。
 */
@Service
public class AuthService {

    public static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        AppUser user = appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(401, LOGIN_FAILED_MESSAGE);
        }
        JwtService.IssuedToken issued = jwtService.sign(user.getUsername(), user.getRole());
        return new LoginResponse(issued.value(), "Bearer", issued.expiresInSeconds(),
                user.getRole(), user.getUsername());
    }
}
