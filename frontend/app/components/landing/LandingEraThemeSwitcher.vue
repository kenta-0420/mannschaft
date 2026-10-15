<script setup lang="ts">
// レトロテーマ表示中にモダン版へ戻るための脱出ボタン
defineEmits<{
  switchToModern: []
}>()

const { t } = useI18n()
</script>

<template>
  <div class="era-switcher">
    <button
      class="era-switcher__btn"
      :aria-label="t('landing.era_themes.switcher.aria_label')"
      :title="t('landing.era_themes.switcher.tooltip')"
      @click="$emit('switchToModern')"
    >
      {{ t('landing.era_themes.switcher.label') }}
    </button>
  </div>
</template>

<style scoped>
/* 常時固定表示（レトロテーマのz-indexより上） */
.era-switcher {
  position: fixed;
  bottom: 16px;
  right: 16px;
  z-index: 9999;
}

.era-switcher__btn {
  background: rgba(0, 0, 0, 0.75);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 14px;
  cursor: pointer;
  backdrop-filter: blur(4px);
  transition: background-color 0.2s ease;
}

/* ホバー・フォーカスでアクセシビリティ確保 */
.era-switcher__btn:hover {
  background: rgba(0, 0, 0, 0.9);
}

.era-switcher__btn:focus-visible {
  outline: 2px solid #ffffff;
  outline-offset: 2px;
  background: rgba(0, 0, 0, 0.9);
}

/* prefers-reduced-motion 対応 */
@media (prefers-reduced-motion: reduce) {
  .era-switcher__btn {
    transition: none;
  }
}
</style>
