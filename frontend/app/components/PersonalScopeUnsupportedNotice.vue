<script setup lang="ts">
/**
 * チーム／組織スコープ専用の設定画面を個人スコープで開いたときの案内（CMP-260903-1358）。
 *
 * 個人スコープでは scopeId が確定せず API を呼べないため、ローディングのまま固まらないよう
 * 画面本体の代わりにこの案内を出す。切替手段は ScopeSwitchHint が示す。
 */
const { t } = useI18n()
</script>

<template>
  <div
    class="rounded-lg border border-surface-200 bg-surface-50 p-4 text-sm text-surface-600 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300"
    data-testid="personal-scope-unsupported"
  >
    <i class="pi pi-info-circle mr-1" />
    {{ t('scopeSwitch.personalUnsupported') }}
    <ScopeSwitchHint class="mt-3" />
  </div>
</template>
