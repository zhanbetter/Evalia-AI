package com.eval.common.util;

import com.eval.common.auth.AuthContext;
import com.eval.common.exception.BusinessException;

/**
 * 数据归属删除保护
 *
 * 共享数据模式下，任何已登录用户都能调用删除接口。本工具规定删除权限：
 *   - created_by 为空（历史无归属数据）→ 仅管理员可删
 *   - created_by 有值 → 创建者本人或管理员可删
 * 其余请求一律拒绝，防止误删他人数据。
 */
public final class OwnershipUtil {

    private OwnershipUtil() {
    }

    /**
     * 校验当前用户是否有权删除该资源，无权时抛业务异常。
     *
     * @param ownerId      资源创建者ID（eval_user.id），历史数据为 null
     * @param ctx          当前登录用户（拦截器注入）
     * @param resourceName 资源类型名，用于错误提示（如「数据集」「评估器」）
     */
    public static void assertCanDelete(Long ownerId, AuthContext ctx, String resourceName) {
        if (ctx == null || ctx.getUserId() == null) {
            throw new BusinessException("未登录，无法删除");
        }
        boolean isAdmin = "ADMIN".equalsIgnoreCase(ctx.getRole());
        if (ownerId == null) {
            if (isAdmin) {
                return;
            }
            throw new BusinessException("该" + resourceName + "为历史数据（无归属），仅管理员可删除");
        }
        if (ownerId.equals(ctx.getUserId()) || isAdmin) {
            return;
        }
        throw new BusinessException("无权删除该" + resourceName + "：仅创建者或管理员可操作");
    }
}