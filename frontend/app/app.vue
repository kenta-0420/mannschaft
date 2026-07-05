<script setup lang="ts">
// html[lang] をアクティブなロケールに追従させる。
// locale が変わるたびにリアクティブに更新されるため、
// リロード後・言語切替後ともに document.documentElement.lang が正しい値になる（AC-12 対応）。
const { locale } = useI18n()
useHead(() => ({
  htmlAttrs: { lang: locale.value },
}))
</script>

<template>
  <NuxtLayout>
    <ActiveIncidentBanner />
    <NuxtPage />
  </NuxtLayout>
  <NavigationLoading />
  <Toast>
    <template #message="{ message }">
      <AppToastMessage :message="message" />
    </template>
  </Toast>
  <ConfirmDialog />
  <DynamicDialog />
  <ErrorReportModal />
</template>

<style>
/* ツールチップ: 小さめの吹き出しスタイル（全体適用） */
.p-tooltip {
  animation-duration: 0.1s !important;
}
.p-tooltip .p-tooltip-text {
  background: white;
  color: #4b5563;
  font-size: 0.7rem;
  padding: 3px 8px;
  border: 1px solid #e5e7eb;
  box-shadow: 0 1px 4px 0 rgb(0 0 0 / 0.08);
  border-radius: 6px;
}
.p-tooltip.p-tooltip-bottom .p-tooltip-arrow {
  border-bottom-color: #e5e7eb;
}
/* ダークモード */
.p-dark .p-tooltip .p-tooltip-text {
  background: #1e293b;
  color: #cbd5e1;
  border-color: #334155;
}
.p-dark .p-tooltip.p-tooltip-bottom .p-tooltip-arrow {
  border-bottom-color: #334155;
}

/* secondary outlined ボタン: #f3efe0 クリーム背景での視認性確保（全ページ共通） */
.p-button-outlined.p-button-secondary {
  background: rgba(255, 255, 255, 0.5) !important;
}
.p-dark .p-button-outlined.p-button-secondary {
  background: rgba(255, 255, 255, 0.08) !important;
}
</style>
