<script setup lang="ts">
const { t } = useI18n()

const features = [
  { icon: '👥', key: 0 },
  { icon: '📅', key: 1 },
  { icon: '💬', key: 2 },
  { icon: '⏱', key: 3 },
  { icon: '📝', key: 4 },
  { icon: '🖼', key: 5 },
]

const steps = [
  { num: '01', key: 0 },
  { num: '02', key: 1 },
  { num: '03', key: 2 },
]
</script>

<template>
  <div class="theme-2005" role="main">
    <!-- グロスヘッダー -->
    <header class="gloss-header" role="banner">
      <div class="header-inner">
        <div class="brand-area">
          <span class="logo-text">Mannschaft</span>
          <span class="beta-badge" aria-label="ベータ版">BETA</span>
        </div>
        <nav class="header-nav" :aria-label="t('landing.layout.nav_label')">
          <NuxtLink to="/login" class="nav-link">{{ t('landing.layout.login') }}</NuxtLink>
          <NuxtLink to="/register" class="orange-btn">{{ t('landing.layout.register') }}</NuxtLink>
        </nav>
      </div>
    </header>

    <!-- ヒーローバナー -->
    <section class="hero-banner" aria-labelledby="hero2005-heading">
      <div class="hero-inner">
        <h1 id="hero2005-heading" class="hero-title">
          {{ t('landing.hero.title_pre') }}<span class="hero-accent">{{ t('landing.hero.title_accent') }}</span>{{ t('landing.hero.title_post') }}
        </h1>
        <p class="hero-subtitle">{{ t('landing.hero.subtitle') }}</p>
        <div class="hero-actions">
          <NuxtLink to="/register" class="orange-btn orange-btn--large">
            {{ t('landing.hero.cta_register') }}
          </NuxtLink>
          <NuxtLink to="/login" class="text-link">
            {{ t('landing.hero.cta_login') }}
          </NuxtLink>
        </div>
      </div>
    </section>

    <!-- 機能カードグリッド -->
    <section class="features-section" aria-labelledby="features2005-heading">
      <div class="section-inner">
        <h2 id="features2005-heading" class="section-heading">{{ t('landing.features.heading') }}</h2>
        <div class="features-grid">
          <div
            v-for="f in features"
            :key="f.key"
            class="feature-card"
          >
            <div class="card-icon" aria-hidden="true">{{ f.icon }}</div>
            <div class="card-title">{{ t(`landing.features.items.${f.key}.title`) }}</div>
            <div class="card-desc">{{ t(`landing.features.items.${f.key}.desc`) }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- ステップ（3カラム水平） -->
    <section class="steps-section" aria-labelledby="steps2005-heading">
      <div class="section-inner">
        <h2 id="steps2005-heading" class="section-heading">{{ t('landing.steps.heading') }}</h2>
        <div class="steps-row">
          <div
            v-for="step in steps"
            :key="step.num"
            class="step-card"
          >
            <div class="step-num" aria-hidden="true">{{ step.num }}</div>
            <div class="step-title">{{ t(`landing.steps.items.${step.key}.title`) }}</div>
            <div class="step-desc">{{ t(`landing.steps.items.${step.key}.desc`) }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- フッターCTA -->
    <section class="footer-cta" aria-labelledby="cta2005-heading">
      <div class="section-inner footer-cta-inner">
        <h2 id="cta2005-heading" class="cta-heading">{{ t('landing.cta.heading') }}</h2>
        <NuxtLink to="/register" class="orange-btn orange-btn--large">
          {{ t('landing.cta.register') }}
        </NuxtLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* =====================
   ベース / 背景
   ===================== */
.theme-2005 {
  min-height: 100vh;
  background-color: #f0f4f8;
  background-image: radial-gradient(#c8d8e8 1px, transparent 1px);
  background-size: 16px 16px;
  font-family: 'Trebuchet MS', Arial, sans-serif;
  color: #333;
}

/* =====================
   ヘッダー（ブルーグロス）
   ===================== */
.gloss-header {
  background: linear-gradient(to bottom, #4a90d9, #2c6fad);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  padding: 10px 0;
}

.header-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* =====================
   ロゴ + BETAバッジ
   ===================== */
.brand-area {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo-text {
  font-size: 1.6rem;
  font-weight: 800;
  background: linear-gradient(135deg, #ffffff 0%, #a8d4ff 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: -0.01em;
}

.beta-badge {
  background: linear-gradient(135deg, #ffd700, #ffa500);
  color: #7a3f00;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 800;
  padding: 2px 6px;
  letter-spacing: 0.08em;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  line-height: 1.4;
}

/* =====================
   ヘッダーナビゲーション
   ===================== */
.header-nav {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-link {
  color: #cce4ff;
  text-decoration: none;
  font-size: 0.875rem;
  transition: color 0.15s ease;
}

.nav-link:hover {
  color: #ffffff;
  text-decoration: underline;
}

/* =====================
   オレンジ光沢ボタン
   ===================== */
.orange-btn {
  display: inline-block;
  padding: 7px 18px;
  background: linear-gradient(to bottom, #ffb347 0%, #ff8c00 50%, #e07800 51%, #ffa500 100%);
  color: #ffffff;
  font-weight: 700;
  font-size: 0.875rem;
  font-family: 'Trebuchet MS', Arial, sans-serif;
  text-decoration: none;
  border-radius: 20px;
  border: 1px solid #c06000;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: filter 0.15s ease, transform 0.1s ease;
}

.orange-btn:hover {
  filter: brightness(1.1);
  transform: translateY(-1px);
}

.orange-btn:active {
  transform: translateY(0);
}

.orange-btn--large {
  padding: 12px 30px;
  font-size: 1rem;
}

/* =====================
   ヒーローバナー
   ===================== */
.hero-banner {
  background: linear-gradient(to bottom, #3a82d4, #2060a8);
  padding: 56px 24px;
  text-align: center;
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-title {
  font-size: 2.4rem;
  font-weight: 800;
  color: #ffffff;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
  line-height: 1.25;
  margin-bottom: 16px;
}

.hero-accent {
  color: #ffe080;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
}

.hero-subtitle {
  font-size: 1rem;
  color: #bcd8f8;
  line-height: 1.65;
  margin-bottom: 28px;
  max-width: 560px;
  margin-left: auto;
  margin-right: auto;
}

.hero-actions {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
}

.text-link {
  color: #ffe080;
  text-decoration: underline;
  font-size: 0.875rem;
  transition: color 0.15s ease;
}

.text-link:hover {
  color: #ffffff;
}

/* =====================
   共通セクション
   ===================== */
.section-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 24px;
}

.section-heading {
  font-size: 1.6rem;
  font-weight: 700;
  color: #1a4a88;
  text-align: center;
  margin-bottom: 28px;
}

/* =====================
   機能カードグリッド
   ===================== */
.features-section {
  padding: 48px 0;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

@media (max-width: 700px) {
  .features-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 440px) {
  .features-grid {
    grid-template-columns: 1fr;
  }
}

.feature-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1), 0 1px 2px rgba(0, 0, 0, 0.06);
  border: 1px solid #dce8f4;
  transition: box-shadow 0.2s ease, transform 0.15s ease;
}

.feature-card:hover {
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.15);
  transform: translateY(-2px);
}

.card-icon {
  font-size: 1.6rem;
  margin-bottom: 10px;
}

.card-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: #1a4a88;
  margin-bottom: 6px;
}

.card-desc {
  font-size: 0.8rem;
  color: #555;
  line-height: 1.55;
}

/* =====================
   ステップ（3カラム水平）
   ===================== */
.steps-section {
  background-color: #e4edf7;
  padding: 48px 0;
}

.steps-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

@media (max-width: 600px) {
  .steps-row {
    grid-template-columns: 1fr;
  }
}

.step-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 24px 20px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  border: 1px solid #c8d8ec;
}

.step-num {
  font-size: 2rem;
  font-weight: 900;
  color: #4a90d9;
  opacity: 0.5;
  line-height: 1;
  margin-bottom: 8px;
}

.step-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: #1a4a88;
  margin-bottom: 6px;
}

.step-desc {
  font-size: 0.8rem;
  color: #555;
  line-height: 1.55;
}

/* =====================
   フッターCTA
   ===================== */
.footer-cta {
  background: linear-gradient(to bottom, #3a82d4, #2060a8);
  padding: 48px 0;
}

.footer-cta-inner {
  text-align: center;
}

.cta-heading {
  font-size: 1.6rem;
  font-weight: 700;
  color: #ffffff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  margin-bottom: 24px;
}

/* =====================
   アクセシビリティ: アニメーション無効化
   ===================== */
@media (prefers-reduced-motion: reduce) {
  .orange-btn,
  .feature-card,
  .nav-link,
  .text-link {
    transition: none;
  }

  .orange-btn:hover,
  .feature-card:hover {
    transform: none;
  }
}
</style>
