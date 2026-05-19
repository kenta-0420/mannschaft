<script setup lang="ts">
// CTAウィンドウパネル
const { t } = useI18n()
</script>

<template>
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
</template>

<style scoped>
/* =====================
   ウィンドウパネル
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
   CTA本体
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

.xp-btn--large {
  padding: 10px 28px;
  font-size: 13px;
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
