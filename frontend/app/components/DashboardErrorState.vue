<script setup lang="ts">
/**
 * 取得失敗を表す共通エラー状態コンポーネント。
 *
 * `DashboardEmptyState`（0件表示）とは別コンポーネント・別 data-testid で描き分ける。
 * 権限エラー・通信断などの取得失敗を「未登録」の空状態へフォールバックさせないために使う
 * （CMP-260922-2045 / 設計は `frontend/app/pages/my/shift-availability.vue` の先行実装を踏襲）。
 */
const props = withDefaults(
  defineProps<{
    /** エラー本文。未指定時は汎用の「データの取得に失敗しました」を表示する。 */
    message?: string
    /** 再試行ボタンを表示するか */
    showRetry?: boolean
    /** ルート要素に付与する data-testid */
    testid?: string
  }>(),
  {
    message: undefined,
    showRetry: true,
    testid: 'load-error-state',
  },
)

const emit = defineEmits<{
  retry: []
}>()

const { t } = useI18n()

const displayMessage = computed(() => props.message ?? t('loadErrorState.message'))
</script>

<template>
  <div
    :data-testid="testid"
    class="flex flex-col items-center justify-center gap-3 py-8 text-center"
  >
    <i class="pi pi-exclamation-triangle text-2xl text-red-500" />
    <p class="text-sm text-surface-700 dark:text-surface-200">{{ displayMessage }}</p>
    <Button
      v-if="showRetry"
      :label="t('loadErrorState.retry')"
      icon="pi pi-refresh"
      severity="secondary"
      outlined
      :data-testid="`${testid}-retry`"
      @click="emit('retry')"
    />
  </div>
</template>
