package com.eval.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/注册成功返回：JWT 令牌 + 用户信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /** JWT 令牌，前端存 localStorage，请求头 Authorization: Bearer xxx */
    private String token;

    private AuthUserVO user;
}