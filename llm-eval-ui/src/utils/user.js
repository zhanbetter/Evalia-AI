/**
 * 登录用户信息读取
 *
 * 登录成功时 authApi 会把 { token, user } 写入 localStorage：
 *   eval-token -> 令牌
 *   eval-user  -> { id, username, nickname, role }
 */
export function readLoggedUser() {
  try {
    const raw = localStorage.getItem('eval-user')
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

/** 当前登录用户名（未登录返回 null） */
export function readLoggedUsername() {
  const user = readLoggedUser()
  return user ? user.username || user.nickname || null : null
}