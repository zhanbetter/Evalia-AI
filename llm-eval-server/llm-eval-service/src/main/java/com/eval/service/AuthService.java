package com.eval.service;

import com.eval.model.dto.LoginDTO;
import com.eval.model.dto.RegisterDTO;
import com.eval.model.vo.AuthUserVO;
import com.eval.model.vo.LoginVO;

/**
 * 登录/注册/鉴权服务
 */
public interface AuthService {

    /**
     * 注册（邀请码模式）：首个注册用户自动成为 ADMIN，其余 USER。
     * 注册成功即签发 JWT，流程上默认「注册即登录」。
     */
    LoginVO register(RegisterDTO dto);

    /** 登录：校验用户名密码与状态，签发新 JWT */
    LoginVO login(LoginDTO dto);

    /** 当前用户信息（不含密码） */
    AuthUserVO me(Long userId);

    /** 退出（JWT 无状态，前端删除令牌即可；保留接口语义） */
    void logout();
}