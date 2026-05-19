<script setup lang="ts">
// ヒーローウィンドウパネル
const { t } = useI18n()
</script>

<template>
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
</template>

<style scoped>
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
   ヒーロー本体
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

/* =====================
   アクセシビリティ: アニメーション無効化
   ===================== */
@media (prefers-reduced-motion: reduce) {
  .xp-btn {
    transition: none;
  }
}
</style>
