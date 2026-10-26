<script setup lang="ts">
const { t } = useI18n()

const features = [
  { key: '0', icon: '👥' },
  { key: '1', icon: '📅' },
  { key: '2', icon: '💬' },
  { key: '3', icon: '🔄' },
]
</script>

<template>
  <div class="neo-page">
    <!-- ヒーローセクション -->
    <header class="neo-hero">
      <div class="neo-hero__inner">
        <p class="neo-hero__brand">Mannschaft</p>
        <h1 class="neo-hero__title">
          {{ t('landing.hero.title_pre') }}
          <span class="neo-hero__accent">{{ t('landing.hero.title_accent') }}</span>
          {{ t('landing.hero.title_post') }}
        </h1>
        <p class="neo-hero__sub">{{ t('landing.hero.subtitle') }}</p>
        <div class="neo-hero__actions">
          <NuxtLink to="/register" class="neo-btn neo-btn--primary">
            {{ t('landing.hero.cta_register') }}
          </NuxtLink>
          <NuxtLink to="/login" class="neo-btn neo-btn--ghost">
            {{ t('landing.hero.cta_login') }}
          </NuxtLink>
        </div>
      </div>
    </header>

    <!-- 機能セクション -->
    <section class="neo-features">
      <div class="neo-features__inner">
        <h2 class="neo-features__heading">{{ t('landing.features.heading') }}</h2>
        <div class="neo-features__grid">
          <div
            v-for="feature in features"
            :key="feature.key"
            class="neo-card"
          >
            <span class="neo-card__icon" aria-hidden="true">{{ feature.icon }}</span>
            <h3 class="neo-card__title">{{ t(`landing.features.items.${feature.key}.title`) }}</h3>
            <p class="neo-card__desc">{{ t(`landing.features.items.${feature.key}.desc`) }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- CTAセクション -->
    <section class="neo-cta">
      <div class="neo-cta__inner">
        <h2 class="neo-cta__heading">{{ t('landing.cta.heading') }}</h2>
        <p class="neo-cta__subheading">{{ t('landing.cta.subheading') }}</p>
        <NuxtLink to="/register" class="neo-btn neo-btn--primary neo-btn--large">
          {{ t('landing.cta.register') }}
        </NuxtLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ===== 基本設定 ===== */
.neo-page {
  min-height: 100vh;
  background-color: #1e1e2e;
  color: #cdd6f4;
  font-family: 'Segoe UI', system-ui, sans-serif;
}

/* ===== ヒーローセクション ===== */
.neo-hero {
  padding: 6rem 1.5rem 4rem;
  display: flex;
  justify-content: center;
}

.neo-hero__inner {
  max-width: 720px;
  width: 100%;
  text-align: center;
}

.neo-hero__brand {
  font-size: 0.875rem;
  font-weight: 700;
  letter-spacing: 0.25em;
  text-transform: uppercase;
  color: #cba6f7;
  margin-bottom: 1.5rem;
}

.neo-hero__title {
  font-size: clamp(2rem, 5vw, 3rem);
  font-weight: 700;
  line-height: 1.3;
  margin-bottom: 1.25rem;
  color: #cdd6f4;
}

.neo-hero__accent {
  color: #cba6f7;
}

.neo-hero__sub {
  font-size: 1.0625rem;
  line-height: 1.7;
  color: #a6adc8;
  margin-bottom: 2.5rem;
}

.neo-hero__actions {
  display: flex;
  gap: 1.25rem;
  justify-content: center;
  flex-wrap: wrap;
}

/* ===== ボタン ===== */
.neo-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.75rem 2rem;
  border-radius: 12px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: box-shadow 0.2s ease, transform 0.1s ease;
  border: none;
}

.neo-btn--primary {
  background: #1e1e2e;
  color: #cba6f7;
  box-shadow: 8px 8px 16px #13131f, -8px -8px 16px #292940;
}

.neo-btn--primary:hover {
  box-shadow: 0 0 20px rgba(203, 166, 247, 0.4), 8px 8px 16px #13131f, -8px -8px 16px #292940;
}

.neo-btn--primary:active {
  box-shadow: inset 4px 4px 8px #13131f, inset -4px -4px 8px #292940;
  transform: scale(0.98);
}

.neo-btn--ghost {
  background: transparent;
  color: #a6adc8;
  box-shadow: 4px 4px 8px #13131f, -4px -4px 8px #292940;
}

.neo-btn--ghost:hover {
  color: #cdd6f4;
  box-shadow: 6px 6px 12px #13131f, -6px -6px 12px #292940;
}

.neo-btn--ghost:active {
  box-shadow: inset 3px 3px 6px #13131f, inset -3px -3px 6px #292940;
  transform: scale(0.98);
}

.neo-btn--large {
  padding: 1rem 2.5rem;
  font-size: 1.125rem;
}

/* ===== 機能セクション ===== */
.neo-features {
  padding: 4rem 1.5rem;
}

.neo-features__inner {
  max-width: 960px;
  margin: 0 auto;
}

.neo-features__heading {
  font-size: 1.75rem;
  font-weight: 700;
  text-align: center;
  margin-bottom: 2.5rem;
  color: #cdd6f4;
}

.neo-features__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.5rem;
}

/* ===== ニューモーフィックカード ===== */
.neo-card {
  background: #1e1e2e;
  border-radius: 16px;
  box-shadow: 8px 8px 16px #13131f, -8px -8px 16px #292940;
  padding: 2rem 1.5rem;
  text-align: center;
  transition: box-shadow 0.2s ease;
}

.neo-card:hover {
  box-shadow: 12px 12px 24px #13131f, -12px -12px 24px #292940;
}

.neo-card__icon {
  font-size: 2rem;
  display: block;
  margin-bottom: 1rem;
}

.neo-card__title {
  font-size: 1rem;
  font-weight: 700;
  color: #cba6f7;
  margin-bottom: 0.5rem;
}

.neo-card__desc {
  font-size: 0.875rem;
  color: #a6adc8;
  line-height: 1.6;
}

/* ===== CTAセクション ===== */
.neo-cta {
  padding: 5rem 1.5rem;
  text-align: center;
}

.neo-cta__inner {
  max-width: 600px;
  margin: 0 auto;
  background: #1e1e2e;
  border-radius: 24px;
  box-shadow: 12px 12px 24px #13131f, -12px -12px 24px #292940;
  padding: 3rem 2rem;
}

.neo-cta__heading {
  font-size: 1.75rem;
  font-weight: 700;
  margin-bottom: 1rem;
  color: #cdd6f4;
}

.neo-cta__subheading {
  font-size: 1rem;
  color: #a6adc8;
  margin-bottom: 2rem;
  line-height: 1.6;
}

/* ===== アニメーション停止対応 ===== */
@media (prefers-reduced-motion: reduce) {
  .neo-btn,
  .neo-card {
    transition: none;
  }
}
</style>
