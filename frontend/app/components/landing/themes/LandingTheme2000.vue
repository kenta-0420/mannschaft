<script setup lang="ts">
const { t } = useI18n()

const features = [
  { icon: '👥', key: 0 },
  { icon: '📅', key: 1 },
  { icon: '💬', key: 2 },
  { icon: '⏱', key: 3 },
]
</script>

<template>
  <div class="theme-2000" role="main">
    <!-- ブラウザ風クロームフレーム -->
    <div class="browser-chrome" role="complementary" aria-label="ブラウザフレーム（装飾）">
      <div class="chrome-toolbar">
        <div class="chrome-buttons" aria-hidden="true">
          <span class="chrome-btn chrome-btn--back">◀</span>
          <span class="chrome-btn chrome-btn--forward">▶</span>
          <span class="chrome-btn chrome-btn--reload">↻</span>
        </div>
        <div class="chrome-address-bar" aria-hidden="true">
          <span class="address-icon">🔒</span>
          <span class="address-url">http://mannschaft.example.jp/</span>
        </div>
        <div class="chrome-actions" aria-hidden="true">
          <span class="chrome-action-btn">★</span>
        </div>
      </div>
    </div>

    <!-- メインレイアウト（2カラム） -->
    <div class="main-layout">
      <!-- 左サイドバー -->
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

      <!-- メインコンテンツエリア -->
      <main class="main-content" id="main-content">
        <!-- ヒーローウィンドウパネル -->
        <section class="glass-window hero-window" aria-labelledby="hero2000-heading">
          <div class="window-titlebar">
            <span class="titlebar-title">Mannschaft — チームの力を最大化するプラットフォーム</span>
            <span class="titlebar-close" aria-hidden="true">●</span>
          </div>
          <div class="window-body hero-body">
            <h1 id="hero2000-heading" class="hero-title">
              {{ t('landing.hero.title_pre') }}<span class="title-accent">{{ t('landing.hero.title_accent') }}</span>{{ t('landing.hero.title_post') }}
            </h1>
            <p class="hero-subtitle">{{ t('landing.hero.subtitle') }}</p>
            <div class="hero-actions">
              <NuxtLink to="/register" class="xp-btn xp-btn--primary">
                {{ t('landing.hero.cta_register') }}
              </NuxtLink>
              <NuxtLink to="/login" class="xp-btn xp-btn--secondary">
                {{ t('landing.hero.cta_login') }}
              </NuxtLink>
            </div>
          </div>
        </section>

        <!-- 機能カードグリッド -->
        <section class="features-grid-section" aria-labelledby="features2000-heading">
          <h2 id="features2000-heading" class="section-heading">{{ t('landing.features.heading') }}</h2>
          <div class="features-grid">
            <div
              v-for="f in features"
              :key="f.key"
              class="glass-panel feature-card"
            >
              <div class="card-icon" aria-hidden="true">{{ f.icon }}</div>
              <div class="card-title">{{ t(`landing.features.items.${f.key}.title`) }}</div>
              <div class="card-desc">{{ t(`landing.features.items.${f.key}.desc`) }}</div>
            </div>
          </div>
        </section>

        <!-- CTAパネル -->
        <section class="glass-window cta-window" aria-labelledby="cta2000-heading">
          <div class="window-titlebar">
            <span class="titlebar-title">今すぐ無料でスタート</span>
            <span class="titlebar-close" aria-hidden="true">●</span>
          </div>
          <div class="window-body cta-body">
            <h2 id="cta2000-heading" class="cta-heading">{{ t('landing.cta.heading') }}</h2>
            <NuxtLink to="/register" class="xp-btn xp-btn--primary xp-btn--large">
              {{ t('landing.cta.register') }}
            </NuxtLink>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* =====================
   ベース / 背景（XP壁紙風）
   ===================== */
.theme-2000 {
  min-height: 100vh;
  background: linear-gradient(180deg, #1a56b0 0%, #2980b9 40%, #a8d8ea 100%);
  font-family: Tahoma, 'MS UI Gothic', sans-serif;
  font-size: 13px;
  color: #f0f8ff;
}

/* =====================
   ブラウザ風クロームフレーム
   ===================== */
.browser-chrome {
  background: linear-gradient(to bottom, #d8e4f0, #b8c8dc);
  border-bottom: 2px solid #7090b0;
  padding: 6px 12px;
}

.chrome-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 1100px;
  margin: 0 auto;
}

.chrome-buttons {
  display: flex;
  gap: 4px;
}

.chrome-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 20px;
  background: linear-gradient(to bottom, #e8f0f8, #c8d8e8);
  border: 1px solid #8090a8;
  border-radius: 3px;
  font-size: 9px;
  color: #404860;
  cursor: default;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  user-select: none;
}

.chrome-address-bar {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #8090a8;
  border-radius: 2px;
  padding: 3px 8px;
  font-size: 11px;
  color: #334;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.15);
}

.address-icon {
  font-size: 10px;
}

.address-url {
  color: #00008b;
}

.chrome-action-btn {
  color: #c0a000;
  font-size: 16px;
  cursor: default;
  user-select: none;
}

/* =====================
   メインレイアウト（2カラム）
   ===================== */
.main-layout {
  display: flex;
  gap: 16px;
  max-width: 1100px;
  margin: 16px auto;
  padding: 0 12px 24px;
  align-items: flex-start;
}

/* =====================
   サイドバー
   ===================== */
.sidebar {
  width: 220px;
  flex-shrink: 0;
}

/* =====================
   ガラスパネル（汎用）
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
   ウィンドウパネル（タイトルバー付き）
   ===================== */
.glass-window {
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  margin-bottom: 16px;
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

.window-body {
  padding: 16px;
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
   メインコンテンツ
   ===================== */
.main-content {
  flex: 1;
  min-width: 0;
}

/* =====================
   ヒーローウィンドウ
   ===================== */
.hero-body {
  padding: 24px;
}

.hero-title {
  font-size: 1.7rem;
  font-weight: 700;
  color: #f0f8ff;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.5);
  line-height: 1.3;
  margin-bottom: 12px;
}

.title-accent {
  color: #ffe080;
}

.hero-subtitle {
  font-size: 0.875rem;
  color: #c8e0f8;
  line-height: 1.65;
  margin-bottom: 20px;
  max-width: 480px;
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

/* =====================
   XP風シルバーボタン
   ===================== */
.xp-btn {
  display: inline-block;
  padding: 6px 18px;
  font-family: Tahoma, 'MS UI Gothic', sans-serif;
  font-size: 12px;
  font-weight: 400;
  text-decoration: none;
  border-radius: 4px;
  cursor: pointer;
  transition: filter 0.1s ease;
  user-select: none;
}

.xp-btn:hover {
  filter: brightness(1.08);
}

.xp-btn:active {
  filter: brightness(0.95);
}

.xp-btn--primary {
  background: linear-gradient(to bottom, #6aa8e0 0%, #4080c0 50%, #2060a8 51%, #4080c0 100%);
  color: #ffffff;
  border: 1px solid #1050a0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.35);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.4);
}

.xp-btn--secondary {
  background: linear-gradient(to bottom, #f0f0f0 0%, #d8d8d8 50%, #c8c8c8 51%, #e0e0e0 100%);
  color: #1a1a1a;
  border: 1px solid #808080;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.xp-btn--large {
  padding: 10px 28px;
  font-size: 13px;
}

/* =====================
   機能グリッド
   ===================== */
.features-grid-section {
  margin-bottom: 16px;
}

.section-heading {
  font-size: 1rem;
  font-weight: 700;
  color: #e0f0ff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
  margin-bottom: 10px;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

@media (max-width: 600px) {
  .features-grid {
    grid-template-columns: 1fr;
  }
}

.feature-card {
  padding: 14px;
}

.card-icon {
  font-size: 1.3rem;
  margin-bottom: 6px;
}

.card-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: #e0f0ff;
  margin-bottom: 4px;
}

.card-desc {
  font-size: 0.75rem;
  color: #b8d8f0;
  line-height: 1.5;
}

/* =====================
   CTAウィンドウ
   ===================== */
.cta-body {
  padding: 24px;
  text-align: center;
}

.cta-heading {
  font-size: 1.1rem;
  font-weight: 700;
  color: #e0f0ff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
  margin-bottom: 16px;
}

/* =====================
   レスポンシブ: サイドバー非表示
   ===================== */
@media (max-width: 640px) {
  .main-layout {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
  }
}

/* =====================
   アクセシビリティ: アニメーション無効化
   ===================== */
@media (prefers-reduced-motion: reduce) {
  .xp-btn,
  .sidebar-link,
  .nav-item {
    transition: none;
  }
}
</style>
