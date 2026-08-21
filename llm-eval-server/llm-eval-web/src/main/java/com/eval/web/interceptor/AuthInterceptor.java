package com.eval.web.interceptor;

import cn.hutool.json.JSONUtil;
import com.eval.common.auth.AuthContext;
import com.eval.common.util.TokenUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器
 *
 * 拦截 /api/**（WebConfig 中配置），放行：
 *   - OPTIONS 预检请求
 *   - /api/auth/login、/api/auth/register（未登录可达）
 * 校验通过后把 AuthContext 放入 request attribute（key: AUTH_ATTR），
 * Controller 用 @RequestAttribute 取值。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** request attribute 键名，Controller 侧 @RequestAttribute("eval.auth") AuthContext 取 */
    public static final String AUTH_ATTR = "eval.auth";

    @Value("${eval.security.jwt-secret:}")
    private String jwtSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        AuthContext ctx = resolve(request);
        if (ctx == null) {
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(AUTH_ATTR, ctx);
        return true;
    }

    private AuthContext resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank() || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return TokenUtil.parse(token, jwtSecret);
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        // 结构对齐 Result 系（code=401），前端 axios 响应拦截器据此跳登录页
        response.getWriter().write(JSONUtil.toJsonStr(
                cn.hutool.json.JSONUtil.createObj()
                        .set("code", 401)
                        .set("message", "未登录或登录已过期，请先登录")
        ));
    }
}