<script setup lang="ts">
// LandingTheme2000: Windows XP 風ランディングテーマ
// 各セクションは landing-theme-2000/ 配下の子コンポーネントに分割
import LandingTheme2000BrowserChrome from './landing-theme-2000/LandingTheme2000BrowserChrome.vue'
import LandingTheme2000Sidebar from './landing-theme-2000/LandingTheme2000Sidebar.vue'
import LandingTheme2000Hero from './landing-theme-2000/LandingTheme2000Hero.vue'
import LandingTheme2000Features from './landing-theme-2000/LandingTheme2000Features.vue'
import LandingTheme2000Cta from './landing-theme-2000/LandingTheme2000Cta.vue'

const features = [
  { icon: '👥', key: 0 },
  { icon: '📅', key: 1 },
  { icon: '💬', key: 2 },
  { icon: '⏱', key: 3 },
] as const
</script>

<template>
  <div class="theme-2000" role="main">
    <LandingTheme2000BrowserChrome />

    <!-- メインレイアウト（2カラム） -->
    <div class="main-layout">
      <LandingTheme2000Sidebar :features="features" />

      <main id="main-content" class="main-content">
        <LandingTheme2000Hero />
        <LandingTheme2000Features :features="features" />
        <LandingTheme2000Cta />
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
   メインコンテンツ
   ===================== */
.main-content {
  flex: 1;
  min-width: 0;
}

/* =====================
   レスポンシブ: サイドバー縦並び化
   ===================== */
@media (max-width: 640px) {
  .main-layout {
    flex-direction: column;
  }
}
</style>
