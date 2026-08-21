package com.eval.common.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.eval.common.auth.AuthContext;

import java.util.Date;

/**
 * JWT 登录令牌工具（hutool 实现，HS256）
 *
 * 签发：payload 携带 userId/username/role/nickname，过期时间由配置 eval.security.jwt-expire-hours 控制。
 * 解析：签名校验（setKey） + 过期校验（JWTUtil.verify）双保险，失败返回 null。
 *
 * 用法约定：前端请求头  Authorization: Bearer &lt;token&gt;
 */
public final class TokenUtil {

    private TokenUtil() {
    }

    private static final String PAYLOAD_USER_ID = "userId";
    private static final String PAYLOAD_USERNAME = "username";
    private static final String PAYLOAD_NICKNAME = "nickname";
    private static final String PAYLOAD_ROLE = "role";

    /**
     * 签发令牌
     *
     * @param user        登录用户信息
     * @param secret      签名密钥（HS256，长度建议 ≥ 32 字节）
     * @param expireHours 有效期（小时）
     */
    public static String issue(AuthContext user, String secret, int expireHours) {
        Date expiresAt = DateUtil.offsetHour(new Date(), Math.max(expireHours, 1));
        return JWT.create()
                .setPayload(PAYLOAD_USER_ID, user.getUserId())
                .setPayload(PAYLOAD_USERNAME, user.getUsername())
                .setPayload(PAYLOAD_NICKNAME, user.getNickname())
                .setPayload(PAYLOAD_ROLE, user.getRole())
                .setExpiresAt(expiresAt)
                .setKey(secret.getBytes())
                .sign();
    }

    /**
     * 解析并校验令牌；签名错误 / 过期 / 载荷缺失时返回 null
     */
    public static AuthContext parse(String token, String secret) {
        if (token == null || token.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }
        JWT jwt;
        try {
            jwt = JWTUtil.parseToken(token);
        } catch (Exception e) {
            return null;
        }
        // 签名 + 过期时间双校验：verify() 只验签名，validate(leeway) 才会校 exp，这里用 validate(0)
        jwt.setKey(secret.getBytes());
        if (!jwt.validate(0)) {
            return null;
        }
        Object userId = jwt.getPayload(PAYLOAD_USER_ID);
        Object username = jwt.getPayload(PAYLOAD_USERNAME);
        Object role = jwt.getPayload(PAYLOAD_ROLE);
        if (userId == null || username == null || role == null) {
            return null;
        }
        AuthContext ctx = new AuthContext();
        ctx.setUserId(userId instanceof Number ? ((Number) userId).longValue() : Long.parseLong(userId.toString()));
        ctx.setUsername(username.toString());
        ctx.setRole(role.toString());
        Object nickname = jwt.getPayload(PAYLOAD_NICKNAME);
        ctx.setNickname(nickname == null ? null : nickname.toString());
        return ctx;
    }
}