<script setup lang="ts">
// サイドバーナビゲーション
// 親から features 一覧（i18n キー番号付き）を props で受け取る
interface FeatureItem {
  icon: string
  key: number
}

defineProps<{
  features: ReadonlyArray<FeatureItem>
}>()

const { t } = useI18n()
</script>

<template>
  <aside class="sidebar glass-panel" aria-label="サイドバーナビゲーション">
    <div class="window-titlebar" aria-hidden="true">
      <span class="titlebar-title">サービス案内</span>
      <span class="titlebar-close">●</span>
    </div>
    <div class="sidebar-body">
      <nav class="sidebar-nav" :aria-label="t('landing.layout.nav_label')">
        <div class="nav-section-label" aria-hidden="true">機能一覧</div>
        <ul class="nav-list">
          <li v-for="f in features" :key="f.key" class="nav-item">
            <span class="nav-bullet" aria-hidden="true">▶</span>
            {{ t(`landing.features.items.${f.key}.title`) }}
          </li>
        </ul>
        <div class="nav-divider" role="separator" />
        <NuxtLink to="/login" class="sidebar-link">
          <span class="nav-bullet" aria-hidden="true">▶</span>{{ t('landing.layout.login') }}
        </NuxtLink>
        <NuxtLink to="/register" class="sidebar-link sidebar-link--highlight">
          <span class="nav-bullet" aria-hidden="true">▶</span>{{ t('landing.layout.register') }}
        </NuxtLink>
      </nav>
    </div>
  </aside>
</template>

<style scoped>
/* =====================
   サイドバー
   ===================== */
.sidebar {
  width: 220px;
  flex-shrink: 0;
}

/* =====================
   ガラスパネル
   ===================== */
.glass-panel {
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

/* =====================
   ウィンドウタイトルバー
   ===================== */
.window-titlebar {
  background: linear-gradient(to right, #0057e7, #0035a8);
  color: white;
  border-radius: 4px 4px 0 0;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: space-between;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.titlebar-close {
  width: 16px;
  height: 16px;
  background: linear-gradient(135deg, #ff6a6a, #cc0000);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0;
  border: 1px solid rgba(0, 0, 0, 0.4);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
  cursor: default;
  user-select: none;
  flex-shrink: 0;
}

/* =====================
   サイドバーコンテンツ
   ===================== */
.sidebar-body {
  padding: 12px;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-section-label {
  font-size: 11px;
  font-weight: 700;
  color: #c0e0ff;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
}

.nav-list {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #d0eaff;
  padding: 3px 4px;
  border-radius: 3px;
  cursor: default;
  user-select: none;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #ffffff;
}

.nav-bullet {
  font-size: 8px;
  color: #80c0ff;
  flex-shrink: 0;
}

.nav-divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.2);
  margin: 8px 0;
}

.sidebar-link {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #d0eaff;
  text-decoration: none;
  padding: 4px 4px;
  border-radius: 3px;
  transition: background-color 0.1s ease;
}

.sidebar-link:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #ffffff;
  text-decoration: underline;
}

.sidebar-link--highlight {
  color: #ffe080;
  font-weight: 700;
}

.sidebar-link--highlight:hover {
  color: #ffee80;
}

/* =====================
   レスポンシブ: サイドバー全幅化
   ===================== */
@media (max-width: 640px) {
  .sidebar {
    width: 100%;
  }
}

/* =====================
   アクセシビリティ: アニメーション無効化
   ===================== */
@media (prefers-reduced-motion: reduce) {
  .sidebar-link,
  .nav-item {
    transition: none;
  }
}
</style>
