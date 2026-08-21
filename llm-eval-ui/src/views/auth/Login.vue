<template>
  <div class="auth-page">
    <div class="auth-card">
      <!-- 左侧品牌面板 -->
      <div class="auth-brand">
        <div class="brand-logo">
          <el-icon :size="26"><MagicStick /></el-icon>
        </div>
        <h1 class="brand-name">Evalia</h1>
        <p class="brand-sub">AI 评测平台</p>
        <p class="brand-bio">
          编写评测 Prompt，让 AI 判定 badcase，量化每一次模型表现。
        </p>
        <ul class="brand-points">
          <li><el-icon :size="14"><CircleCheck /></el-icon> 数据集 · 模型 · 评估器一体化管理</li>
          <li><el-icon :size="14"><CircleCheck /></el-icon> 多维判定 · 结果分析 · badcase 追溯</li>
          <li><el-icon :size="14"><CircleCheck /></el-icon> 人工审核 · 金标准 · 一致性统计</li>
        </ul>
        <div class="brand-foot">Evalia · 让评测可复现、可沉淀</div>
      </div>

      <!-- 右侧登录/注册表单 -->
      <div class="auth-form">
        <el-tabs v-model="mode" class="auth-tabs">
          <el-tab-pane label="登录" name="login" />
          <el-tab-pane label="注册" name="register" />
        </el-tabs>

        <div class="auth-body">
          <!-- 登录 -->
          <el-form v-if="mode === 'login'" :model="loginForm" label-position="top" @submit.prevent="doLogin">
            <el-form-item label="用户名">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" size="large" autofocus @keyup.enter="doLogin" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" size="large" @keyup.enter="doLogin" />
            </el-form-item>
            <el-button type="primary" size="large" class="auth-submit" :loading="loginLoading" native-type="submit">
              登 录
            </el-button>
          </el-form>

          <!-- 注册 -->
          <el-form v-else :model="regForm" label-position="top" @submit.prevent="doRegister">
            <div class="reg-grid">
              <el-form-item label="用户名">
                <el-input v-model="regForm.username" placeholder="2~32 位中文/字母/数字" size="large" @keyup.enter="doRegister" />
              </el-form-item>
              <el-form-item label="昵称（可选）">
                <el-input v-model="regForm.nickname" placeholder="展示用昵称" size="large" @keyup.enter="doRegister" />
              </el-form-item>
            </div>
            <el-form-item label="密码">
              <el-input v-model="regForm.password" type="password" show-password placeholder="至少 6 位" size="large" @keyup.enter="doRegister" />
            </el-form-item>
            <el-form-item label="确认密码">
              <el-input v-model="regForm.confirm" type="password" show-password placeholder="再次输入密码" size="large" @keyup.enter="doRegister" />
            </el-form-item>
            <el-form-item label="邀请码">
              <el-input v-model="regForm.inviteCode" placeholder="请输入邀请码" size="large">
                <template #prefix><el-icon><Key /></el-icon></template>
              </el-input>
            </el-form-item>
            <el-button type="primary" size="large" class="auth-submit" :loading="regLoading" native-type="submit">
              注册并登录
            </el-button>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../api'

export default {
  name: 'AuthPage',
  setup() {
    const route = useRoute()
    const router = useRouter()

    const mode = ref('login')
    const loginLoading = ref(false)
    const regLoading = ref(false)

    const loginForm = reactive({ username: '', password: '' })
    const regForm = reactive({ username: '', nickname: '', password: '', confirm: '', inviteCode: '' })

    const persistLogin = (data) => {
      localStorage.setItem('eval-token', data.token)
      localStorage.setItem('eval-user', JSON.stringify(data.user))
    }

    const goHome = () => {
      const redirect = route.query.redirect
      router.replace(redirect && redirect !== '/login' ? redirect : '/dataset')
    }

    const doLogin = async () => {
      if (!loginForm.username || !loginForm.password) {
        ElMessage.warning('请输入用户名和密码')
        return
      }
      loginLoading.value = true
      try {
        const res = await authApi.login(loginForm)
        persistLogin(res.data)
        ElMessage.success(`欢迎回来，${res.data.user.nickname || res.data.user.username}`)
        goHome()
      } finally {
        loginLoading.value = false
      }
    }

    const doRegister = async () => {
      if (!regForm.username || !regForm.password) {
        ElMessage.warning('请填写用户名和密码')
        return
      }
      if (regForm.password.length < 6) {
        ElMessage.warning('密码至少 6 位')
        return
      }
      if (regForm.password !== regForm.confirm) {
        ElMessage.warning('两次输入的密码不一致')
        return
      }
      if (!regForm.inviteCode) {
        ElMessage.warning('请输入邀请码')
        return
      }
      regLoading.value = true
      try {
        const res = await authApi.register({
          username: regForm.username,
          nickname: regForm.nickname,
          password: regForm.password,
          inviteCode: regForm.inviteCode
        })
        persistLogin(res.data)
        ElMessage.success('注册成功，开始使用吧')
        goHome()
      } finally {
        regLoading.value = false
      }
    }

    return { mode, loginForm, regForm, loginLoading, regLoading, doLogin, doRegister }
  }
}
</script>

<style scoped>
.auth-page {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-card {
  width: 100%;
  max-width: 880px;
  min-height: 520px;
  display: grid;
  grid-template-columns: 360px 1fr;
  border-radius: 20px;
  overflow: hidden;
  border: 1px solid var(--border);
  box-shadow: 0 24px 70px rgba(2, 32, 20, 0.12);
  background: var(--bg-card);
}

/* ---- 品牌面板 ---- */
.auth-brand {
  background: linear-gradient(160deg, #0f9d6d 0%, #059669 45%, #047857 100%);
  color: #fff;
  padding: 40px 32px;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}
.auth-brand::after {
  content: '';
  position: absolute;
  right: -60px;
  bottom: -60px;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.auth-brand::before {
  content: '';
  position: absolute;
  right: 20px;
  top: -80px;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
}
.brand-logo {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 22px;
}
.brand-name {
  font-size: 30px;
  font-weight: 800;
  letter-spacing: -0.5px;
  line-height: 1.1;
}
.brand-sub {
  font-size: 14px;
  opacity: 0.85;
  margin-top: 4px;
  font-weight: 500;
}
.brand-bio {
  margin-top: 22px;
  font-size: 13px;
  line-height: 1.7;
  opacity: 0.9;
}
.brand-points {
  list-style: none;
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 12.5px;
  opacity: 0.92;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand-foot {
  margin-top: auto;
  font-size: 12px;
  opacity: 0.7;
}

/* ---- 表单 ---- */
.auth-form {
  padding: 30px 40px 26px;
  display: flex;
  flex-direction: column;
}
.auth-tabs {
  flex-shrink: 0;
}
.auth-tabs :deep(.el-tabs__nav) {
  margin-left: 0;
}
.auth-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-top: 6px;
}
.reg-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.auth-submit {
  width: 100%;
  margin-top: 6px;
  font-size: 14px;
  letter-spacing: 2px;
}

@media (max-width: 860px) {
  .auth-card {
    grid-template-columns: 1fr;
    max-width: 460px;
  }
  .auth-brand {
    display: none;
  }
}
</style>