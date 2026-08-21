package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台用户（登录/注册/鉴权）
 *
 * 密码存 BCrypt 哈希（hutool BCrypt，$2a$ 前缀）；首个注册用户由注册逻辑自动赋予 ADMIN，
 * 其余默认 USER。注册需通过配置里的邀请码校验（eval.security.register-code）。
 */
@Data
@TableName("eval_user")
public class EvalUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名（唯一） */
    private String username;

    /** BCrypt 哈希 —— 仅在服务端内部使用，永不序列化给前端 */
    @JsonIgnore
    private String password;

    /** 昵称 */
    private String nickname;

    /** 角色: ADMIN-管理员 USER-普通用户 */
    private String role;

    /** 状态: 1-启用 0-禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}