package com.eval.web.controller;

import com.eval.common.auth.AuthContext;
import com.eval.common.result.Result;
import com.eval.model.dto.LoginDTO;
import com.eval.model.dto.RegisterDTO;
import com.eval.model.vo.AuthUserVO;
import com.eval.model.vo.LoginVO;
import com.eval.service.AuthService;
import com.eval.web.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 登录 / 注册 / 鉴权
 *
 *   POST /api/auth/register   注册（邀请码模式，注册即登录）
 *   POST /api/auth/login      登录
 *   GET  /api/auth/me         当前用户信息（需登录）
 *   POST /api/auth/logout     退出（JWT 无状态，前端删令牌即可）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody RegisterDTO dto) {
        return Result.success(authService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @GetMapping("/me")
    public Result<AuthUserVO> me(@RequestAttribute(AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        return Result.success(authService.me(ctx.getUserId()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute(AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        authService.logout();
        return Result.success();
    }
}