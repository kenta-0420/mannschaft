<script setup lang="ts">
// F02.9 Phase 2 — お気に入りウィジェットの空状態。
// 0件時に表示されるシンプルなプレースホルダ。
const { t } = useI18n()
</script>

<template>
  <div
    class="text-center py-8 text-surface-500 dark:text-surface-400"
    data-testid="favorites-widget-empty"
  >
    <div class="text-4xl mb-2" aria-hidden="true">⭐</div>
    <p class="text-sm">{{ t('favorites.empty') }}</p>
  </div>
</template>
