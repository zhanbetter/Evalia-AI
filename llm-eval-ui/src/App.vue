<template>
  <div class="app-root" :class="[theme, { 'nav-collapsed': collapsed }]">
    <!-- 左侧导航栏 -->
    <aside class="glass-side">
      <div class="side-brand">
        <div class="brand-icon">
          <el-icon :size="18"><MagicStick /></el-icon>
        </div>
        <span class="brand-text" v-show="!collapsed">LLM Eval</span>
      </div>

      <nav class="side-nav">
        <div class="nav-group-label" v-show="!collapsed">评测管理</div>

        <router-link v-for="item in navItems.primary" :key="item.to" :to="item.to" class="nav-item" v-slot="{ isActive }">
          <div :class="['nav-pill', { active: isActive }]">
            <span class="nav-icon" :style="{ background: isActive ? item.grad : item.gradDim, color: isActive ? '#fff' : item.color }">
              <el-icon :size="16"><component :is="item.icon" /></el-icon>
            </span>
            <span class="nav-label">{{ item.label }}</span>
            <span class="nav-badge" v-if="item.badge" :style="{ background: item.grad }">{{ item.badge }}</span>
          </div>
        </router-link>

        <div class="nav-group-label" v-show="!collapsed">结果分析</div>

        <router-link v-for="item in navItems.secondary" :key="item.to" :to="item.to" class="nav-item" v-slot="{ isActive }">
          <div :class="['nav-pill', { active: isActive }]">
            <span class="nav-icon" :style="{ background: isActive ? item.grad : item.gradDim, color: isActive ? '#fff' : item.color }">
              <el-icon :size="16"><component :is="item.icon" /></el-icon>
            </span>
            <span class="nav-label">{{ item.label }}</span>
          </div>
        </router-link>
      </nav>

      <div class="side-footer">
        <button class="theme-toggle" @click="toggleTheme" :title="theme==='theme-dark'?'切换亮色':'切换暗色'">
          <el-icon :size="15"><component :is="theme==='theme-dark' ? 'Sunny' : 'Moon'" /></el-icon>
        </button>
        <button class="collapse-toggle" @click="collapsed=!collapsed" :title="collapsed ? '展开侧边栏' : '收起侧边栏'">
          <el-icon :size="14"><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
          <span v-show="!collapsed">收起</span>
        </button>
      </div>
    </aside>

    <!-- 右侧内容 -->
    <main class="main-area">
      <div class="main-inner">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script>
export default {
  name: 'App',
  data() {
    return {
      collapsed: false,
      theme: localStorage.getItem('eval-theme') || 'theme-light',
      navItems: {
        primary: [
          { to: '/dataset', label: '数据集', icon: 'Files', color: '#10b981', grad: 'linear-gradient(135deg,#10b981,#059669)', gradDim: 'rgba(16,185,129,0.12)' },
          { to: '/model', label: '模型', icon: 'Cpu', color: '#6366f1', grad: 'linear-gradient(135deg,#6366f1,#4f46e5)', gradDim: 'rgba(99,102,241,0.12)' },
          { to: '/prompt', label: '评估器', icon: 'ChatLineSquare', color: '#f59e0b', grad: 'linear-gradient(135deg,#f59e0b,#d97706)', gradDim: 'rgba(245,158,11,0.12)' },
          { to: '/task', label: '任务', icon: 'List', color: '#ec4899', grad: 'linear-gradient(135deg,#ec4899,#db2777)', gradDim: 'rgba(236,72,153,0.12)' }
        ],
        secondary: [
          { to: '/analysis', label: '结果分析', icon: 'DataAnalysis', color: '#06b6d4', grad: 'linear-gradient(135deg,#06b6d4,#0891b2)', gradDim: 'rgba(6,182,212,0.12)' }
        ]
      }
    }
  },
  methods: {
    toggleTheme() {
      this.theme = this.theme === 'theme-dark' ? 'theme-light' : 'theme-dark'
      localStorage.setItem('eval-theme', this.theme)
      this.applyTheme()
    },
    applyTheme() {
      document.documentElement.classList.toggle(
        'dark',
        this.theme === 'theme-dark'
      )
    }
  },
  mounted() {
    this.applyTheme()
  }
}
</script>

<style>
/* ==========================================
   全局 CSS 变量 — 双主题
   ========================================== */
:root,
.theme-light {
  --bg-root: #f0f2f5;
  --bg-card: #ffffff;
  --bg-card-hover: #f8fafc;
  --bg-input: #f1f5f9;
  --bg-input-focus: #ffffff;
  --bg-dropdown: #ffffff;
  --bg-overlay: rgba(0,0,0,0.15);
  --text-prime: #0f172a;
  --text-sec: #475569;
  --text-mute: #94a3b8;
  --border: #e2e8f0;
  --border-strong: #cbd5e1;
  --accent: #10b981;
  --accent-soft: rgba(16,185,129,0.08);
  --accent-hover: #059669;
  --accent-text: #ffffff;
  --red: #ef4444;
  --red-soft: rgba(239,68,68,0.08);
  --yellow: #f59e0b;
  --yellow-soft: rgba(245,158,11,0.08);
  --brand-gradient: linear-gradient(135deg, #10b981, #059669);
  --side-bg: rgba(255,255,255,0.86);
  --side-border: rgba(0,0,0,0.04);
  --shadow-xs: 0 1px 2px rgba(0,0,0,0.04);
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04);
  --shadow-card: 0 4px 12px rgba(0,0,0,0.04), 0 1px 3px rgba(0,0,0,0.05);
}

.theme-dark {
  --bg-root: #0a0f14;
  --bg-card: rgba(15,23,42,0.65);
  --bg-card-hover: rgba(30,41,59,0.7);
  --bg-input: rgba(255,255,255,0.05);
  --bg-input-focus: rgba(255,255,255,0.08);
  --bg-dropdown: #111827;
  --bg-overlay: rgba(0,0,0,0.55);
  --text-prime: #f1f5f9;
  --text-sec: #94a3b8;
  --text-mute: #64748b;
  --border: rgba(255,255,255,0.07);
  --border-strong: rgba(255,255,255,0.13);
  --accent: #34d399;
  --accent-soft: rgba(52,211,153,0.12);
  --accent-hover: #2dd4a1;
  --accent-text: #000000;
  --red: #f87171;
  --red-soft: rgba(248,113,113,0.12);
  --yellow: #fbbf24;
  --yellow-soft: rgba(251,191,36,0.12);
  --brand-gradient: linear-gradient(135deg, #34d399, #059669);
  --side-bg: rgba(14,23,18,0.92);
  --side-border: rgba(255,255,255,0.06);
  --shadow-xs: none;
  --shadow-sm: none;
  --shadow-card: 0 4px 16px rgba(0,0,0,0.15), 0 0 0 1px rgba(255,255,255,0.05);
}

/* ==========================================
   基础重置 + 主题感知
   ========================================== */
* { margin: 0; padding: 0; box-sizing: border-box; }

html, body, #app {
  height: 100%;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: var(--bg-root);
  color: var(--text-prime);
  font-size: 14px;
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
  transition: background 0.3s, color 0.3s;
}

.app-root {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ==========================================
   侧边栏 — 毛玻璃，双色适配
   ========================================== */
.glass-side {
  width: 200px;
  flex-shrink: 0;
  background: var(--side-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--side-border);
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
  z-index: 10;
}
.nav-collapsed .glass-side { width: 60px; }

.side-brand {
  padding: 20px 14px 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-icon {
  width: 30px; height: 30px;
  background: var(--brand-gradient);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 15px;
  color: var(--accent-text);
  flex-shrink: 0;
}
.brand-text {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-prime);
  white-space: nowrap;
  letter-spacing: -0.5px;
}

.side-nav {
  flex: 1;
  padding: 4px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow-y: auto;
}
.nav-group-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--text-mute);
  padding: 12px 12px 4px;
  font-weight: 600;
}
.nav-item { text-decoration: none; display:block; }
.nav-pill {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.18s ease;
  position: relative;
}
.nav-pill:hover {
  background: var(--accent-soft);
}
.nav-pill.active {
  background: var(--accent-soft);
  box-shadow: inset 0 0 0 1px var(--accent-soft);
}
.nav-icon {
  width: 30px; height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.2s ease;
}
.nav-pill.active .nav-icon {
  box-shadow: 0 2px 6px rgba(0,0,0,0.2);
  transform: scale(1.05);
}
.nav-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-sec);
  white-space: nowrap;
  transition: color 0.2s;
}
.nav-pill.active .nav-label { color: var(--text-prime); font-weight: 600; }
.nav-pill:hover .nav-label { color: var(--text-prime); }
.nav-badge {
  margin-left: auto;
  font-size: 10px;
  font-weight: 600;
  color: #fff;
  padding: 1px 6px;
  border-radius: 8px;
}
.nav-collapsed .nav-label,
.nav-collapsed .nav-badge,
.nav-collapsed .nav-group-label { display: none; }
.nav-collapsed .nav-pill { justify-content: center; padding: 8px; }
.nav-collapsed .nav-icon { width: 32px; height: 32px; }

.side-footer {
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-top: 1px solid var(--border);
}
.theme-toggle {
  background: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px 8px;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  color: var(--text-sec);
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.theme-toggle:hover { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.collapse-toggle {
  background: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px 10px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-sec);
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 1;
  justify-content: center;
  white-space: nowrap;
  overflow: hidden;
}
.collapse-toggle:hover { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.nav-collapsed .collapse-toggle span { display: none; }
.nav-collapsed .collapse-toggle { padding: 5px 8px; }
/* 收起状态下 footer 纵向排列，避免溢出 */
.nav-collapsed .side-footer {
  flex-direction: column;
  padding: 12px 10px;
  gap: 6px;
}
.nav-collapsed .side-footer .theme-toggle,
.nav-collapsed .side-footer .collapse-toggle {
  width: 100%;
  padding: 5px 0;
  box-sizing: border-box;
}

/* ==========================================
   主区域
   ========================================== */
.main-area {
  flex: 1;
  overflow: hidden;
  height: 100vh;
}
.main-inner {
  height: 100%;
  padding: 20px;
  /* 固定宽度：宽屏稳定不变，窄屏最多占满可用区域 */
  width: 1280px;
  max-width: 100%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
}
/* 页面容器撑满高度，内部元素自己控制滚动 */
.main-inner > * {
  flex: 1;
  min-height: 0;
  height: 100%;
}

/* ==========================================
   通用卡片（页面容器）
   ========================================== */
.page-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 24px;
  box-shadow: var(--shadow-card);
  transition: background 0.3s, border-color 0.3s;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.page-card .page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-card .page-header h2 {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-prime);
}
/* 卡片内表格与分页器间距统一 */
.page-card .el-table {
  margin-top: 4px;
}
.page-card .el-table + .el-pagination {
  margin-top: 18px;
  margin-bottom: 2px;
}
.page-card .el-pagination {
  margin-top: 18px;
  justify-content: flex-end;
}

/* ===== 页面容器内：标题/分页固定，数据区独立滚动 ===== */
.page-card .page-header {
  flex-shrink: 0;
}
.page-card .stats-row {
  flex-shrink: 0;
}
.page-card .el-pagination {
  flex-shrink: 0;
}
/* 数据区滚动：卡片网格 + 表格 */
.page-card .ds-grid,
.page-card .model-grid,
.page-card .prompt-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
/* 表格场景：表头固定，表体滚动由 el-table 自带处理，
   但需要让表格容器占据弹性空间 */
.page-card > .el-table {
  flex: 1;
  min-height: 0;
}
.page-card > .el-table + .el-pagination {
  flex-shrink: 0;
}

/* ==========================================
   全局 Element Plus 样式美化
   ========================================== */

/* ----- 按钮 ----- */
.el-button {
  border-radius: 8px;
  font-weight: 500;
  font-size: 13px;
  transition: all 0.2s;
}
.el-button--primary {
  --el-button-bg-color: var(--accent);
  --el-button-border-color: var(--accent);
  --el-button-hover-bg-color: var(--accent-hover);
  --el-button-hover-border-color: var(--accent-hover);
  --el-button-text-color: var(--accent-text);
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}
.el-button--default {
  --el-button-bg-color: var(--bg-input);
  --el-button-border-color: var(--border);
  --el-button-text-color: var(--text-prime);
  box-shadow: var(--shadow-xs);
}
.el-button--small { border-radius: 6px; font-size: 12px; }
.el-button--danger {
  --el-button-bg-color: var(--red-soft);
  --el-button-border-color: transparent;
  --el-button-text-color: var(--red);
  --el-button-hover-bg-color: rgba(239,68,68,0.2);
}

/* ----- 表格 ----- */
.el-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--bg-input);
  --el-table-row-hover-bg-color: var(--accent-soft);
  --el-table-border-color: var(--border);
  --el-table-header-text-color: var(--text-sec);
  --el-table-text-color: var(--text-prime);
  font-size: 13px;
  border-radius: 12px;
  overflow: hidden;
}
.el-table th.el-table__cell {
  font-weight: 600;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  border-bottom: 2px solid var(--border) !important;
  padding: 14px 12px;
  background: var(--bg-input);
}
.el-table td.el-table__cell {
  border-bottom: 1px solid var(--border) !important;
  padding: 13px 12px;
}
.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell {
  background: var(--bg-card-hover);
}
.el-table__body tr:hover > td.el-table__cell {
  background: var(--accent-soft) !important;
}

/* ----- 分页 ----- */
.el-pagination {
  --el-pagination-button-bg-color: transparent;
  --el-pagination-hover-color: var(--accent);
}
.el-pagination .el-pager li {
  background: transparent !important;
  color: var(--text-sec);
  border-radius: 8px;
  font-weight: 500;
}
.el-pagination .el-pager li.is-active {
  background: var(--accent-soft) !important;
  color: var(--accent) !important;
  font-weight: 600;
  border-radius: 8px;
}

/* ----- 输入框（重点美化） ----- */
.el-input__wrapper {
  background: var(--bg-input) !important;
  border: 1.5px solid var(--border) !important;
  border-radius: 8px !important;
  box-shadow: var(--shadow-xs) !important;
  transition: all 0.2s !important;
  padding: 1px 12px !important;
}
.el-input__wrapper:hover {
  border-color: var(--border-strong) !important;
  background: var(--bg-input-focus) !important;
}
.el-input__wrapper.is-focus {
  border-color: var(--accent) !important;
  background: var(--bg-input-focus) !important;
  box-shadow: 0 0 0 3px var(--accent-soft) !important;
}
/* 暗色模式下聚焦光圈更明显 */
.theme-dark .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 3px rgba(52,211,153,0.25) !important;
}
.el-input__inner { color: var(--text-prime); font-size: 13px; }
.el-input__inner::placeholder { color: var(--text-mute); font-size: 12px; }

/* 文本域同样美化 */
.el-textarea__inner {
  background: var(--bg-input) !important;
  border: 1.5px solid var(--border) !important;
  border-radius: 8px !important;
  color: var(--text-prime);
  font-size: 13px;
  box-shadow: var(--shadow-xs) !important;
  transition: all 0.2s;
  padding: 10px 12px !important;
}
.el-textarea__inner:hover { border-color: var(--border-strong) !important; }
.el-textarea__inner:focus {
  border-color: var(--accent) !important;
  box-shadow: 0 0 0 3px var(--accent-soft) !important;
}
.el-textarea__inner::placeholder { color: var(--text-mute); font-size: 12px; }

/* ----- 下拉选择器 ----- */
.el-select .el-input__wrapper {
  background: var(--bg-input) !important;
  border: 1.5px solid var(--border) !important;
  border-radius: 8px !important;
}
.el-select .el-input__wrapper:hover { border-color: var(--border-strong) !important; }
.el-select .el-input.is-focus .el-input__wrapper,
.el-select .el-input__wrapper.is-focus {
  border-color: var(--accent) !important;
  box-shadow: 0 0 0 3px var(--accent-soft) !important;
}
.el-select-dropdown {
  background: var(--bg-dropdown) !important;
  border: 1px solid var(--border) !important;
  border-radius: 10px !important;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12) !important;
}
.el-select-dropdown__item {
  color: var(--text-sec);
  font-size: 13px;
  transition: all 0.12s;
  padding: 6px 12px;
  height: auto;
  line-height: 1.5;
  white-space: nowrap;
}
.el-select-dropdown__item.hover { background: var(--accent-soft); color: var(--accent); }
.el-select-dropdown__item.selected { color: var(--accent); font-weight: 600; background: var(--accent-soft); }

/* ----- 下拉菜单 (Dropdown) ----- */
.el-dropdown-menu {
  background: var(--bg-dropdown) !important;
  border: 1px solid var(--border) !important;
  border-radius: 10px !important;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12) !important;
  padding: 6px !important;
}
.el-dropdown-menu__item {
  color: var(--text-sec) !important;
  font-size: 13px !important;
  border-radius: 6px !important;
  padding: 8px 14px !important;
  transition: all 0.12s !important;
  line-height: 1.5 !important;
}
.el-dropdown-menu__item:not(.is-disabled):hover {
  background: var(--accent-soft) !important;
  color: var(--accent) !important;
}
.el-dropdown-menu__item.is-disabled { color: var(--text-mute) !important; }
.el-dropdown-menu__item .el-icon { color: var(--text-mute); }
.el-dropdown-menu__item:hover .el-icon { color: var(--accent); }

/* ----- 滑块 (Slider) ----- */
.el-slider__bar { background: var(--accent) !important; }
.el-slider__button {
  border-color: var(--accent) !important;
  width: 14px; height: 14px;
}
.el-slider__runway { background: var(--bg-input); }

/* ----- 数字输入 (InputNumber) ----- */
.el-input-number .el-input__wrapper { padding: 1px 8px !important; }

/* ----- 复选框 / 单选框 ----- */
.el-checkbox__label { color: var(--text-sec); font-size: 13px; }
.el-checkbox__input.is-checked .el-checkbox__inner { background: var(--accent); border-color: var(--accent); }
.el-checkbox__inner { border-radius: 4px; }
.el-radio__label { color: var(--text-sec); font-size: 13px; }
.el-radio__input.is-checked .el-radio__inner { background: var(--accent); border-color: var(--accent); }
.el-radio__input.is-checked + .el-radio__label { color: var(--accent); }

/* ----- 弹窗 (Dialog) ----- */
.el-dialog {
  --el-dialog-bg-color: var(--bg-card);
  border-radius: 16px;
  border: 1px solid var(--border);
  box-shadow: 0 20px 60px rgba(0,0,0,0.25);
}
.el-dialog__header { padding: 22px 24px 0; }
.el-dialog__body { padding: 18px 24px 22px; }
.el-dialog__title { color: var(--text-prime); font-weight: 700; font-size: 16px; }
.el-overlay { background-color: var(--bg-overlay) !important; }

/* ----- 标签 (Tag) ----- */
.el-tag {
  border-radius: 5px;
  font-weight: 500;
  font-size: 11px;
  border: none;
  padding: 2px 8px;
}
.el-tag--success { background: var(--accent-soft); color: var(--accent); }
.el-tag--info    { background: rgba(148,163,184,0.1); color: var(--text-sec); }
.el-tag--warning { background: var(--yellow-soft); color: var(--yellow); }
.el-tag--danger  { background: var(--red-soft); color: var(--red); }

/* ----- 消息 / 确认框 ----- */
.el-message { border-radius: 10px; }
.el-message-box {
  --el-messagebox-bg-color: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 14px;
}
.el-message-box__title { color: var(--text-prime); }
.el-message-box__message { color: var(--text-sec); }

/* ----- 提示 (Alert) ----- */
.el-alert--info {
  background: var(--accent-soft);
  border: 1px solid rgba(16,185,129,0.2);
  border-radius: 10px;
}
.el-alert__title { color: var(--accent); }

/* ----- 空状态 ----- */
.el-empty__description p { color: var(--text-mute); }

/* ----- 进度条 ----- */
.el-progress-bar__outer { background: var(--bg-input); border-radius: 4px; }
.el-progress-bar__inner { background: var(--accent); border-radius: 4px; }
.el-progress__text { color: var(--text-sec); }

/* ----- 步骤条 ----- */
.el-step__title { font-size: 12px; color: var(--text-sec); font-weight: 500; }
.el-step__title.is-process { color: var(--accent); font-weight: 600; }
.el-step__head.is-process { color: var(--accent); border-color: var(--accent); }
.el-step__head.is-finish { color: var(--accent); border-color: var(--accent); }

/* ----- 分割线 ----- */
.el-divider { border-color: var(--border); }

/* ----- 链接 ----- */
.el-link.el-link--primary { --el-link-text-color: var(--accent); }
.el-link.el-link--primary:hover { --el-link-hover-text-color: var(--accent-hover); }

/* ----- Tabs ----- */
.el-tabs__item { color: var(--text-sec); font-size: 13px; }
.el-tabs__item.is-active { color: var(--accent); }
.el-tabs__active-bar { background: var(--accent); }
.el-tabs__nav-wrap::after { background: var(--border); }

/* ----- 表单标签 ----- */
.el-form-item__label { color: var(--text-sec); font-size: 13px; font-weight: 500; }

/* ----- 开关 (Switch) ----- */
.el-switch__core { border-radius: 12px !important; }

/* ==========================================
   旧页面样式兼容适配（result / badcase）
   ========================================== */
.theme-light .page {
  background: var(--bg-root);
  color: var(--text-prime);
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 14px;
  line-height: 1.5;
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px;
}
.theme-dark .page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-prime);
}
/* 统一 result/badcase 页面容器：宽度与主区一致、高度撑满、内部滚动 */
.page {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
}
.filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }

/* 通用卡片（result/badcase 旧版用） */
.section {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  border-radius: 14px !important;
  margin-bottom: 20px;
  overflow: hidden;
  box-shadow: var(--shadow-card);
  transition: background 0.3s;
}
.sec-hd { padding: 18px 24px 0; }
.sec-hd h2 { font-size: 15px; font-weight: 700; color: var(--text-prime); }
.sec-bd { padding: 16px 24px 22px; }
.kpi {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  box-shadow: var(--shadow-card);
}
.tag { display:inline-block; background:var(--accent-soft); border-radius:20px; padding:2px 10px; font-size:12px; color:var(--accent); margin:4px 4px 0 0; }

/* Badcase 卡片内的暗色/亮色适配 */
.theme-dark .bc-card { border-color: var(--border) !important; }
.theme-dark .bc-head { background: var(--bg-input) !important; }
.theme-dark .tab-btn { background: var(--bg-input); border-color: var(--border); color: var(--text-sec); }
.theme-dark .tab-btn.active { background: var(--accent); border-color: var(--accent); color: var(--accent-text); }
.theme-dark .ind-table thead th { background: var(--bg-input); border-bottom-color: var(--border); color: var(--text-sec) !important; }
.theme-dark .ind-table tbody td { border-bottom-color: var(--border); color: var(--text-prime) !important; }

/* ============ 暗色模式下强制补白（Element Plus 自带暗色变量不可控） ============ */
html.dark .el-button--default,
html.dark .el-button:not(.el-button--primary):not(.el-button--danger):not(.el-button--success):not(.el-button--warning) {
  border-color: var(--border) !important;
  background-color: var(--bg-input) !important;
  color: var(--text-prime) !important;
}
html.dark .el-dialog {
  background: var(--bg-card) !important;
  border-color: var(--border) !important;
}
html.dark .el-select-dropdown {
  background: var(--bg-dropdown) !important;
  border-color: var(--border) !important;
}
html.dark .el-dropdown-menu {
  background: var(--bg-dropdown) !important;
  border-color: var(--border) !important;
}
html.dark .el-popper.is-light {
  background: var(--bg-card) !important;
  border-color: var(--border) !important;
}
.theme-dark .el-input-number .el-input__wrapper {
  background: var(--bg-input) !important;
  border-color: var(--border) !important;
}
.theme-dark .el-step__title.is-wait,
.theme-dark .el-step__title.is-process,
.theme-dark .el-step__title.is-finish {
  color: var(--text-sec) !important;
}
.theme-dark .el-step__head.is-wait,
.theme-dark .el-step__head.is-process,
.theme-dark .el-step__head.is-finish {
  color: var(--accent) !important;
  border-color: var(--accent) !important;
}
.theme-dark .report-header { color: #fff !important; }

/* 分页按钮强制暗色（Element Plus 暗色模式没覆盖 prev/next 按钮） */
.theme-dark .el-pagination .btn-prev,
.theme-dark .el-pagination .btn-next,
.theme-dark .el-pagination .el-pager li {
  background: var(--bg-input) !important;
  color: var(--text-sec) !important;
  border: 1px solid var(--border) !important;
  border-radius: 8px;
}
.theme-dark .el-pagination .btn-prev:hover:not(:disabled),
.theme-dark .el-pagination .btn-next:hover:not(:disabled),
.theme-dark .el-pagination .el-pager li:hover:not(.is-active) {
  background: var(--accent-soft) !important;
  color: var(--accent) !important;
}
.theme-dark .el-pagination .el-pager li.is-active {
  background: var(--accent-soft) !important;
  color: var(--accent) !important;
  font-weight: 600;
  border-radius: 8px;
}
.theme-dark .el-pagination .btn-prev:disabled,
.theme-dark .el-pagination .btn-next:disabled {
  background: transparent !important;
  color: var(--text-mute) !important;
  border-color: transparent !important;
}

/* ===== 分页按钮与图标统一紧凑尺寸 ===== */
.el-pagination .btn-prev,
.el-pagination .btn-next {
  min-width: 28px !important;
  height: 28px !important;
  line-height: 28px !important;
}
.el-pagination .el-pager li {
  min-width: 28px !important;
  height: 28px !important;
  line-height: 28px !important;
}
.el-pagination .btn-prev .el-icon,
.el-pagination .btn-next .el-icon {
  font-size: 11px !important;
}
.el-pagination .el-pager li .el-icon {
  font-size: 11px !important;
}

/* 亮色模式清理 */
.theme-light .dataset-detail,
.theme-light .task-create-page {
  background: transparent !important;
}
</style>
