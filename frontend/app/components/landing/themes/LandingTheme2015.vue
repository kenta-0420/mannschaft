<script setup lang="ts">
const { t } = useI18n()

const features = [
  { key: '0', icon: 'group' },
  { key: '1', icon: 'event' },
  { key: '2', icon: 'chat' },
  { key: '3', icon: 'swap_horiz' },
  { key: '4', icon: 'assignment' },
  { key: '5', icon: 'photo_library' },
]

const steps = [
  { key: '0', number: '1' },
  { key: '1', number: '2' },
  { key: '2', number: '3' },
]
</script>

<template>
  <div class="md-page">
    <!-- AppBar -->
    <header class="md-appbar" role="banner">
      <div class="md-appbar__inner">
        <span class="md-appbar__title">Mannschaft</span>
        <nav class="md-appbar__nav" :aria-label="t('landing.layout.nav_label')">
          <NuxtLink to="/login" class="md-appbar__link">
            {{ t('landing.layout.login') }}
          </NuxtLink>
          <NuxtLink to="/register" class="md-raised-btn md-raised-btn--accent">
            {{ t('landing.layout.register') }}
          </NuxtLink>
        </nav>
      </div>
    </header>

    <main id="main-content">
      <!-- ヒーローセクション -->
      <section class="md-hero" aria-labelledby="hero-title">
        <div class="md-hero__inner">
          <h1 id="hero-title" class="md-hero__title">
            {{ t('landing.hero.title_pre') }}
            <span class="md-hero__accent">{{ t('landing.hero.title_accent') }}</span>
            {{ t('landing.hero.title_post') }}
          </h1>
          <p class="md-hero__sub">{{ t('landing.hero.subtitle') }}</p>
          <NuxtLink to="/register" class="md-raised-btn md-raised-btn--white md-ripple">
            {{ t('landing.hero.cta_register') }}
          </NuxtLink>
        </div>
      </section>

      <!-- 機能カードグリッド -->
      <section class="md-features" aria-labelledby="features-heading">
        <div class="md-features__inner">
          <h2 id="features-heading" class="md-section-heading">
            {{ t('landing.features.heading') }}
          </h2>
          <p class="md-section-subheading">{{ t('landing.features.subheading') }}</p>
          <div class="md-card-grid">
            <div
              v-for="feature in features"
              :key="feature.key"
              class="md-card"
            >
              <div class="md-card__icon-wrap" aria-hidden="true">
                <span class="md-card__icon-text">{{ feature.icon }}</span>
              </div>
              <div class="md-card__body">
                <h3 class="md-card__title">{{ t(`landing.features.items.${feature.key}.title`) }}</h3>
                <p class="md-card__desc">{{ t(`landing.features.items.${feature.key}.desc`) }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ステップセクション -->
      <section class="md-steps" aria-labelledby="steps-heading">
        <div class="md-steps__inner">
          <h2 id="steps-heading" class="md-section-heading md-section-heading--dark">
            {{ t('landing.steps.heading') }}
          </h2>
          <div class="md-timeline">
            <div
              v-for="step in steps"
              :key="step.key"
              class="md-timeline__item"
            >
              <div class="md-timeline__number" aria-hidden="true">{{ step.number }}</div>
              <div class="md-timeline__connector" aria-hidden="true" />
              <div class="md-timeline__content">
                <h3 class="md-timeline__title">{{ t(`landing.steps.items.${step.key}.title`) }}</h3>
                <p class="md-timeline__desc">{{ t(`landing.steps.items.${step.key}.desc`) }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- CTAセクション -->
      <section class="md-cta" aria-labelledby="cta-heading">
        <div class="md-cta__inner">
          <h2 id="cta-heading" class="md-cta__heading">{{ t('landing.cta.heading') }}</h2>
          <p class="md-cta__subheading">{{ t('landing.cta.subheading') }}</p>
          <NuxtLink to="/register" class="md-raised-btn md-raised-btn--accent md-raised-btn--large md-ripple">
            {{ t('landing.cta.register') }}
          </NuxtLink>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* ===== 基本設定 ===== */
.md-page {
  min-height: 100vh;
  background-color: #fafafa;
  color: #212121;
  font-family: 'Roboto', 'Noto Sans JP', sans-serif;
}

/* ===== AppBar ===== */
.md-appbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background-color: #1976d2;
  height: 64px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.24), 0 4px 8px rgba(0, 0, 0, 0.12);
  display: flex;
  align-items: center;
}

.md-appbar__inner {
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 0 1.5rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.md-appbar__title {
  font-size: 1.25rem;
  font-weight: 500;
  color: #ffffff;
  letter-spacing: 0.02em;
}

.md-appbar__nav {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.md-appbar__link {
  color: rgba(255, 255, 255, 0.87);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 0.375rem 0.5rem;
  border-radius: 2px;
  transition: background-color 0.2s ease;
}

.md-appbar__link:hover {
  background-color: rgba(255, 255, 255, 0.12);
}

/* ===== Raised Button ===== */
.md-raised-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 1rem;
  height: 36px;
  border-radius: 2px;
  font-size: 0.875rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  text-decoration: none;
  cursor: pointer;
  border: none;
  position: relative;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.24), 0 1px 2px rgba(0, 0, 0, 0.12);
  transition: box-shadow 0.2s ease;
}

.md-raised-btn:hover {
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.24), 0 2px 4px rgba(0, 0, 0, 0.12);
}

.md-raised-btn:active {
  box-shadow: 0 8px 16px rgba(0, 0, 0, 0.24), 0 4px 8px rgba(0, 0, 0, 0.12);
}

.md-raised-btn--accent {
  background-color: #ff4081;
  color: #ffffff;
}

.md-raised-btn--accent:hover {
  background-color: #f50057;
}

.md-raised-btn--white {
  background-color: #ffffff;
  color: #1976d2;
}

.md-raised-btn--white:hover {
  background-color: #f5f5f5;
}

.md-raised-btn--large {
  height: 48px;
  padding: 0 2rem;
  font-size: 1rem;
}

/* ===== Rippleエフェクト ===== */
.md-ripple::after {
  content: '';
  display: block;
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.3);
  border-radius: inherit;
  opacity: 0;
  transform: scale(0);
  pointer-events: none;
  transition: transform 0.4s ease, opacity 0.4s ease;
}

.md-ripple:active::after {
  transform: scale(2.5);
  opacity: 1;
  transition: none;
}

/* ===== ヒーローセクション ===== */
.md-hero {
  background-color: #1976d2;
  padding: 5rem 1.5rem 4rem;
  text-align: center;
}

.md-hero__inner {
  max-width: 720px;
  margin: 0 auto;
}

.md-hero__title {
  font-size: clamp(1.75rem, 4.5vw, 2.75rem);
  font-weight: 300;
  color: #ffffff;
  line-height: 1.3;
  margin-bottom: 1.25rem;
}

.md-hero__accent {
  font-weight: 700;
  color: #ff4081;
}

.md-hero__sub {
  font-size: 1rem;
  color: rgba(255, 255, 255, 0.87);
  line-height: 1.7;
  margin-bottom: 2.5rem;
}

/* ===== セクション共通 ===== */
.md-section-heading {
  font-size: 1.5rem;
  font-weight: 400;
  text-align: center;
  margin-bottom: 0.75rem;
  color: #1976d2;
}

.md-section-heading--dark {
  color: #212121;
}

.md-section-subheading {
  font-size: 0.9375rem;
  color: #757575;
  text-align: center;
  margin-bottom: 2.5rem;
  line-height: 1.6;
}

/* ===== 機能カードグリッド ===== */
.md-features {
  padding: 4rem 1.5rem;
  background-color: #fafafa;
}

.md-features__inner {
  max-width: 1100px;
  margin: 0 auto;
}

.md-card-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
}

/* ===== マテリアルカード ===== */
.md-card {
  background: #ffffff;
  border-radius: 2px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12), 0 1px 2px rgba(0, 0, 0, 0.24);
  overflow: hidden;
  transition: box-shadow 0.2s ease;
}

.md-card:hover {
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.16), 0 2px 4px rgba(0, 0, 0, 0.24);
}

.md-card__icon-wrap {
  background-color: #1976d2;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.md-card__icon-text {
  font-size: 0.75rem;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.87);
  text-transform: uppercase;
  letter-spacing: 0.1em;
}

.md-card__body {
  padding: 1.25rem;
}

.md-card__title {
  font-size: 1rem;
  font-weight: 500;
  color: #212121;
  margin-bottom: 0.5rem;
}

.md-card__desc {
  font-size: 0.875rem;
  color: #757575;
  line-height: 1.6;
}

/* ===== ステップ（タイムライン）セクション ===== */
.md-steps {
  padding: 4rem 1.5rem;
  background-color: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.md-steps__inner {
  max-width: 900px;
  margin: 0 auto;
}

.md-timeline {
  display: flex;
  align-items: flex-start;
  gap: 0;
  margin-top: 2rem;
}

.md-timeline__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}

.md-timeline__number {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background-color: #1976d2;
  color: #ffffff;
  font-size: 1.25rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.24);
  flex-shrink: 0;
  z-index: 1;
}

.md-timeline__connector {
  position: absolute;
  top: 24px;
  left: calc(50% + 24px);
  right: calc(-50% + 24px);
  height: 2px;
  background-color: #e0e0e0;
}

.md-timeline__item:last-child .md-timeline__connector {
  display: none;
}

.md-timeline__content {
  margin-top: 1rem;
  text-align: center;
  padding: 0 1rem;
}

.md-timeline__title {
  font-size: 0.9375rem;
  font-weight: 500;
  color: #212121;
  margin-bottom: 0.375rem;
}

.md-timeline__desc {
  font-size: 0.8125rem;
  color: #757575;
  line-height: 1.6;
}

/* ===== CTAセクション ===== */
.md-cta {
  padding: 5rem 1.5rem;
  text-align: center;
  background-color: #fafafa;
}

.md-cta__inner {
  max-width: 600px;
  margin: 0 auto;
  background: #ffffff;
  border-radius: 2px;
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.12), 0 2px 4px rgba(0, 0, 0, 0.24);
  padding: 3rem 2rem;
}

.md-cta__heading {
  font-size: 1.5rem;
  font-weight: 400;
  color: #1976d2;
  margin-bottom: 1rem;
}

.md-cta__subheading {
  font-size: 0.9375rem;
  color: #757575;
  margin-bottom: 2rem;
  line-height: 1.6;
}

/* ===== レスポンシブ ===== */
@media (max-width: 768px) {
  .md-card-grid {
    grid-template-columns: 1fr;
  }

  .md-timeline {
    flex-direction: column;
    align-items: center;
    gap: 2rem;
  }

  .md-timeline__connector {
    display: none;
  }
}

@media (max-width: 480px) {
  .md-appbar__nav {
    gap: 0.5rem;
  }

  .md-appbar__link {
    display: none;
  }
}

/* ===== アニメーション停止対応 ===== */
@media (prefers-reduced-motion: reduce) {
  .md-raised-btn,
  .md-card,
  .md-appbar__link {
    transition: none;
  }

  .md-ripple::after {
    transition: none;
  }
}
</style>
