<script setup lang="ts">
import type { LoadErrorKind } from '~/utils/loadError'
import { classifyLoadError } from '~/utils/loadError'

/** 取得失敗を空状態と区別し、原因に応じた穏やかな案内を表示する共通部品。 */
const props = withDefaults(defineProps<{
  /** catch したエラー。HTTP状態や通信断の判定に使い、生の内容は画面へ表示しない。 */
  error?: unknown
  /** API契約上の事情などで自動判定を上書きする場合の表示種別。 */
  kind?: LoadErrorKind
  /** 個別画面で見出しを上書きする場合の文言。 */
  title?: string
  /** 個別画面で本文を上書きする場合の文言。 */
  message?: string
  /** 再試行ボタンの表示を明示的に上書きする。 */
  showRetry?: boolean
  /** ルート要素に付与する data-testid。 */
  testid?: string
}>(), {
  error: undefined,
  kind: undefined,
  title: undefined,
  message: undefined,
  showRetry: undefined,
  testid: undefined,
})

const emit = defineEmits<{
  retry: []
}>()

const { t } = useI18n()

const displayKind = computed<LoadErrorKind>(() => {
  if (props.kind) return props.kind
  if (props.error !== undefined) return classifyLoadError(props.error)
  return 'generic'
})
const displayTitle = computed(
  () => props.title ?? t(`loadErrorState.states.${displayKind.value}.title`),
)
const displayMessage = computed(
  () => props.message ?? t(`loadErrorState.states.${displayKind.value}.message`),
)
const shouldShowRetry = computed(() => {
  if (props.showRetry !== undefined) return props.showRetry
  return !['forbidden', 'notFoundOrForbidden'].includes(displayKind.value)
})
const icon = computed(() => {
  if (displayKind.value === 'forbidden') return 'pi pi-lock'
  if (displayKind.value === 'notFoundOrForbidden') return 'pi pi-search'
  if (displayKind.value === 'network') return 'pi pi-wifi'
  return 'pi pi-exclamation-circle'
})
const testid = computed(() => props.testid ?? 'load-error-state')
</script>

<template>
  <div
    :data-testid="testid"
    class="flex flex-col items-center justify-center gap-3 py-8 text-center"
  >
    <i :class="[icon, 'text-2xl text-amber-500']" aria-hidden="true" />
    <div class="space-y-1">
      <p class="font-medium text-surface-800 dark:text-surface-100">{{ displayTitle }}</p>
      <p class="text-sm text-surface-600 dark:text-surface-300">{{ displayMessage }}</p>
    </div>
    <Button
      v-if="shouldShowRetry"
      :label="t('loadErrorState.retry')"
      icon="pi pi-refresh"
      severity="secondary"
      outlined
      :data-testid="`${testid}-retry`"
      @click="emit('retry')"
    />
  </div>
</template>
