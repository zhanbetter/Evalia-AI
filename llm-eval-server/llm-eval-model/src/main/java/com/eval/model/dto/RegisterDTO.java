package com.eval.model.dto;

import lombok.Data;

/**
 * 注册请求（邀请码模式）
 */
@Data
public class RegisterDTO {

    /** 登录名（唯一） */
    private String username;

    /** 密码（明文，服务端 BCrypt 加密存储） */
    private String password;

    /** 昵称（可选） */
    private String nickname;

    /** 邀请码（必须与配置 eval.security.register-code 一致） */
    private String inviteCode;
}