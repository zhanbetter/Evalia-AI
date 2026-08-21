package com.eval.model.vo;

import lombok.Data;

/**
 * 登录/当前用户信息（不含密码）
 */
@Data
public class AuthUserVO {

    private Long id;

    private String username;

    private String nickname;

    /** ADMIN-管理员 USER-普通用户 */
    private String role;
}