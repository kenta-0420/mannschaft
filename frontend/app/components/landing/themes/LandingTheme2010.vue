<script setup lang="ts">
const { t } = useI18n()

const features = [
  { icon: '⚙', key: 0 },
  { icon: '📅', key: 1 },
  { icon: '💬', key: 2 },
  { icon: '🕐', key: 3 },
]
</script>

<template>
  <div class="theme-2010" role="main">
    <!-- 革製ヘッダー -->
    <header class="leather-header" role="banner">
      <div class="header-inner">
        <div class="brand-logo">
          <span class="logo-gloss">Mannschaft</span>
        </div>
        <nav class="pill-nav" :aria-label="t('landing.layout.nav_label')">
          <NuxtLink to="/login" class="pill-tab">{{ t('landing.layout.login') }}</NuxtLink>
          <NuxtLink to="/register" class="pill-tab pill-tab--active">{{ t('landing.layout.register') }}</NuxtLink>
        </nav>
      </div>
    </header>

    <!-- ヒーローパネル（フェルト質感） -->
    <section class="hero-section" aria-labelledby="hero2010-heading">
      <div class="felt-panel hero-panel">
        <div class="stitched-border" aria-hidden="true" />
        <div class="panel-content">
          <h1 id="hero2010-heading" class="hero-title">
            {{ t('landing.hero.title_pre') }}<span class="title-accent">{{ t('landing.hero.title_accent') }}</span>{{ t('landing.hero.title_post') }}
          </h1>
          <p class="hero-subtitle">{{ t('landing.hero.subtitle') }}</p>
          <div class="cta-buttons">
            <NuxtLink to="/register" class="gloss-btn gloss-btn--primary">
              {{ t('landing.hero.cta_register') }}
            </NuxtLink>
            <NuxtLink to="/login" class="gloss-btn gloss-btn--secondary">
              {{ t('landing.hero.cta_login') }}
            </NuxtLink>
          </div>
        </div>
      </div>
    </section>

    <!-- 機能リスト（革製バインダー風） -->
    <section class="features-section" aria-labelledby="features2010-heading">
      <div class="binder-panel">
        <div class="stitched-border" aria-hidden="true" />
        <div class="panel-content">
          <h2 id="features2010-heading" class="section-heading">{{ t('landing.features.heading') }}</h2>
          <div class="features-list">
            <div
              v-for="f in features"
              :key="f.key"
              class="feature-row"
            >
              <div class="feature-icon-col" aria-hidden="true">
                <div class="icon-badge">{{ f.icon }}</div>
              </div>
              <div class="feature-text-col">
                <div class="feature-title">{{ t(`landing.features.items.${f.key}.title`) }}</div>
                <div class="feature-desc">{{ t(`landing.features.items.${f.key}.desc`) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- CTAパネル -->
    <section class="cta-section" aria-labelledby="cta2010-heading">
      <div class="metallic-panel">
        <div class="stitched-border" aria-hidden="true" />
        <div class="panel-content panel-content--center">
          <h2 id="cta2010-heading" class="cta-heading">{{ t('landing.cta.heading') }}</h2>
          <NuxtLink to="/register" class="gloss-btn gloss-btn--primary gloss-btn--large">
            {{ t('landing.cta.register') }}
          </NuxtLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* =====================
   ベース / 背景（革テクスチャ）
   ===================== */
.theme-2010 {
  min-height: 100vh;
  background-color: #8b4513;
  background-image: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 2px,
    rgba(0, 0, 0, 0.1) 2px,
    rgba(0, 0, 0, 0.1) 4px
  );
  font-family: Georgia, 'Times New Roman', serif;
  color: #f5f0dc;
}

/* =====================
   ヘッダー
   ===================== */
.leather-header {
  background-color: #6b2f0a;
  background-image: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 2px,
    rgba(0, 0, 0, 0.15) 2px,
    rgba(0, 0, 0, 0.15) 4px
  );
  border-bottom: 3px solid #4a1f05;
  box-shadow: 0 3px 8px rgba(0, 0, 0, 0.5);
  padding: 12px 0;
}

.header-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo-gloss {
  font-size: 1.6rem;
  font-weight: 700;
  font-family: Georgia, serif;
  background: linear-gradient(to bottom, #ffe8b0 0%, #d4a050 50%, #a06020 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-shadow: none;
  letter-spacing: 0.04em;
}

/* =====================
   ナビゲーション（光沢ピル型タブ）
   ===================== */
.pill-nav {
  display: flex;
  gap: 8px;
}

.pill-tab {
  display: inline-block;
  padding: 6px 18px;
  border-radius: 20px;
  font-size: 0.85rem;
  font-family: Georgia, serif;
  color: #f5f0dc;
  text-decoration: none;
  background: linear-gradient(to bottom, #a06030 0%, #6b3510 50%, #5a2a08 51%, #7a4020 100%);
  border: 1px solid rgba(0, 0, 0, 0.4);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.2);
  transition: filter 0.15s ease;
}

.pill-tab:hover {
  filter: brightness(1.15);
}

.pill-tab--active {
  background: linear-gradient(to bottom, #5ba3e0 0%, #2171c7 50%, #1a5ca8 51%, #2980b9 100%);
  border-color: rgba(0, 0, 0, 0.5);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

/* =====================
   パネル共通
   ===================== */
.felt-panel {
  background-color: #2d5016;
  background-image:
    url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='4' height='4'%3E%3Crect width='1' height='1' fill='rgba(0,0,0,0.07)'/%3E%3C/svg%3E");
  border-radius: 10px;
  border: 2px solid #1c3a0c;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.05);
  position: relative;
  overflow: hidden;
}

.metallic-panel {
  background: linear-gradient(to bottom, #9a9a9a 0%, #6e6e6e 50%, #5a5a5a 51%, #7a7a7a 100%);
  border-radius: 10px;
  border: 2px solid #404040;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.3);
  position: relative;
  overflow: hidden;
}

/* ステッチ縫い目（疑似境界線） */
.stitched-border {
  position: absolute;
  inset: 6px;
  border: 2px dashed rgba(255, 255, 255, 0.15);
  border-radius: 6px;
  pointer-events: none;
}

.panel-content {
  padding: 40px;
  position: relative;
  z-index: 1;
}

.panel-content--center {
  text-align: center;
}

/* =====================
   ヒーローセクション
   ===================== */
.hero-section {
  max-width: 960px;
  margin: 32px auto;
  padding: 0 24px;
}

.hero-title {
  font-size: 2.4rem;
  font-weight: 700;
  color: #f5f0dc;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.7);
  line-height: 1.3;
  margin-bottom: 16px;
}

.title-accent {
  color: #ffd080;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.8), 0 0 12px rgba(255, 200, 80, 0.3);
}

.hero-subtitle {
  font-size: 1rem;
  color: #d4c8a8;
  line-height: 1.7;
  margin-bottom: 28px;
  max-width: 560px;
}

.cta-buttons {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/* =====================
   光沢グラデーションボタン
   ===================== */
.gloss-btn {
  display: inline-block;
  padding: 10px 24px;
  border-radius: 8px;
  font-family: Georgia, serif;
  font-size: 0.95rem;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: filter 0.15s ease, transform 0.1s ease;
}

.gloss-btn:hover {
  filter: brightness(1.12);
  transform: translateY(-1px);
}

.gloss-btn:active {
  transform: translateY(0);
}

.gloss-btn--primary {
  background: linear-gradient(to bottom, #5ba3e0 0%, #2171c7 50%, #1a5ca8 51%, #2980b9 100%);
  color: #ffffff;
  border: 1px solid #1050a0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.gloss-btn--secondary {
  background: linear-gradient(to bottom, #b09060 0%, #7a6030 50%, #6a5020 51%, #907040 100%);
  color: #f5f0dc;
  border: 1px solid #4a3010;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.gloss-btn--large {
  padding: 14px 36px;
  font-size: 1.1rem;
}

/* =====================
   機能セクション（バインダー風）
   ===================== */
.features-section {
  max-width: 960px;
  margin: 24px auto;
  padding: 0 24px;
}

.section-heading {
  font-size: 1.6rem;
  font-weight: 700;
  color: #f5f0dc;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.6);
  margin-bottom: 24px;
}

.features-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.feature-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.feature-row:last-child {
  border-bottom: none;
}

.feature-icon-col {
  flex-shrink: 0;
}

.icon-badge {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  background: linear-gradient(to bottom, #b09060 0%, #7a6030 50%, #6a5020 51%, #907040 100%);
  border: 1px solid #4a3010;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
}

.feature-title {
  font-size: 1rem;
  font-weight: 700;
  color: #f5f0dc;
  margin-bottom: 4px;
}

.feature-desc {
  font-size: 0.875rem;
  color: #c8b890;
  line-height: 1.5;
}

/* =====================
   CTAセクション
   ===================== */
.cta-section {
  max-width: 960px;
  margin: 24px auto 48px;
  padding: 0 24px;
}

.cta-heading {
  font-size: 1.8rem;
  font-weight: 700;
  color: #f5f0dc;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.5);
  margin-bottom: 24px;
}

/* =====================
   アクセシビリティ: アニメーション無効化
   ===================== */
@media (prefers-reduced-motion: reduce) {
  .gloss-btn,
  .pill-tab {
    transition: none;
  }

  .gloss-btn:hover {
    transform: none;
  }
}
</style>
