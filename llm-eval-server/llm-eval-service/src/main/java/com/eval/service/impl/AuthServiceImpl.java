package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.auth.AuthContext;
import com.eval.common.exception.BusinessException;
import com.eval.common.util.TokenUtil;
import com.eval.dao.mapper.EvalUserMapper;
import com.eval.model.dto.LoginDTO;
import com.eval.model.dto.RegisterDTO;
import com.eval.model.entity.EvalUser;
import com.eval.model.vo.AuthUserVO;
import com.eval.model.vo.LoginVO;
import com.eval.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 登录/注册服务实现（无 Spring Security，纯 hutool：BCrypt 密码 + JWT 令牌）
 *
 * 角色规则：用户表为空时首个注册用户 → ADMIN，其后注册均为 USER。
 * 邀请码规则：配置 eval.security.register-code 为空 → 关闭注册（仅能登录）；
 *             非空 → 注册时必须提交一致的邀请码，否则拒绝。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_]{2,32}$");

    private final EvalUserMapper userMapper;

    @Value("${eval.security.register-code:}")
    private String registerCode;

    @Value("${eval.security.jwt-secret:}")
    private String jwtSecret;

    @Value("${eval.security.jwt-expire-hours:72}")
    private int jwtExpireHours;

    @Override
    public LoginVO register(RegisterDTO dto) {
        // 1) 邀请码
        if (StrUtil.isBlank(registerCode)) {
            throw new BusinessException("当前未开放自助注册，请联系管理员创建账号");
        }
        if (!registerCode.equals(StrUtil.trim(dto.getInviteCode()))) {
            throw new BusinessException("邀请码不正确，请向管理员索取后重试");
        }

        // 2) 参数校验
        String username = StrUtil.trim(dto.getUsername());
        String password = dto.getPassword();
        if (StrUtil.isBlank(username) || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException("用户名需为 2~32 位中文/字母/数字/下划线");
        }
        if (StrUtil.isBlank(password) || password.length() < 6) {
            throw new BusinessException("密码至少 6 位");
        }

        // 3) 用户名唯一
        if (userMapper.selectCount(new LambdaQueryWrapper<EvalUser>()
                .eq(EvalUser::getUsername, username)) > 0) {
            throw new BusinessException("用户名已被占用，请换一个");
        }

        // 4) 首个用户自动升为管理员
        boolean firstUser = userMapper.selectCount(new LambdaQueryWrapper<EvalUser>()) == 0;

        EvalUser user = new EvalUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password));
        user.setNickname(StrUtil.blankToDefault(StrUtil.trim(dto.getNickname()), username));
        user.setRole(firstUser ? "ADMIN" : "USER");
        user.setStatus(1);
        userMapper.insert(user);

        log.info("新用户注册: {} role={}", username, user.getRole());
        return buildLoginVO(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        String username = StrUtil.trim(dto.getUsername());
        if (StrUtil.isBlank(username) || StrUtil.isBlank(dto.getPassword())) {
            throw new BusinessException("请输入用户名和密码");
        }
        EvalUser user = userMapper.selectOne(new LambdaQueryWrapper<EvalUser>()
                .eq(EvalUser::getUsername, username));
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }
        log.info("用户登录: {}", username);
        return buildLoginVO(user);
    }

    @Override
    public AuthUserVO me(Long userId) {
        EvalUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return toAuthUserVO(user);
    }

    @Override
    public void logout() {
        // JWT 无状态：前端删除本地令牌即可完成退出
    }

    // ---------- helpers ----------

    private LoginVO buildLoginVO(EvalUser user) {
        AuthContext ctx = new AuthContext(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
        String token = TokenUtil.issue(ctx, jwtSecret, jwtExpireHours);
        return new LoginVO(token, toAuthUserVO(user));
    }

    private AuthUserVO toAuthUserVO(EvalUser user) {
        AuthUserVO vo = new AuthUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        return vo;
    }
}