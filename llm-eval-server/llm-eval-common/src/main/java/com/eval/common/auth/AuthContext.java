package com.eval.common.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前登录用户上下文（由 JWT 解析注入，随请求传递）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 登录名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 角色: ADMIN-管理员 USER-普通用户 */
    private String role;
}