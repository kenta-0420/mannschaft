<script setup lang="ts">
/**
 * F11.1 Phase 5: 同期結果トースト通知コンポーネント。
 *
 * useOfflineSync の syncAll 完了後に呼び出し、
 * 同期結果のサマリーをトーストで表示する。
 * コンフリクトがある場合はタップでコンフリクト一覧へ遷移する。
 */
const { t } = useI18n()
const toast = useToast()
const router = useRouter()

/**
 * 同期結果をトースト通知する。
 */
function showSyncResult(result: { success: number; failed: number; conflicts: number }) {
  const total = result.success + result.failed + result.conflicts

  if (total === 0) return

  if (result.conflicts > 0) {
    toast.add({
      severity: 'warn',
      summary: t('sync.complete'),
      detail: t('sync.result_with_conflict', {
        total,
        success: result.success,
        conflicts: result.conflicts,
      }),
      life: 8000,
    })
  } else if (result.failed > 0) {
    toast.add({
      severity: 'error',
      summary: t('sync.failed'),
      detail: t('sync.result_summary', { total, success: result.success }),
      life: 5000,
    })
  } else {
    toast.add({
      severity: 'success',
      summary: t('sync.complete'),
      detail: t('sync.result_summary', { total, success: result.success }),
      life: 3000,
    })
  }
}

/**
 * コンフリクト一覧ページへ遷移する。
 */
function navigateToConflicts() {
  router.push('/sync/conflicts')
}

defineExpose({
  showSyncResult,
  navigateToConflicts,
})
</script>

<template>
  <!-- レンダリング不要のロジック専用コンポーネント -->
  <slot />
</template>
