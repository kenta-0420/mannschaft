<script setup lang="ts">
/**
 * F12.5 Phase 2-D — GitHub Issue 作成 / 表示ボタン。
 *
 * 3 状態を表示する：
 *   1. 未設定（githubEnabled=false） → ボタン無効化、未設定メッセージ表示
 *   2. 未作成（githubIssueUrl=null）  → 「GitHub Issue を作成」ボタン
 *   3. 作成済（githubIssueUrl!=null） → 「GitHub で見る」リンク
 */
import type { ErrorReportDetail, ErrorReportSeverity } from '~/types/error-report'

const props = defineProps<{
  /** 対象のエラーレポート（githubIssueUrl を含む）。 */
  report: ErrorReportDetail
  /** サーバーから取得した GitHub 連携の有効状態（NULL は未取得）。 */
  githubEnabled: boolean | null
  /** 作成中フラグ。 */
  loading?: boolean
}>()

const emit = defineEmits<{
  /** 作成完了を親へ通知（親は再 fetch する想定）。 */
  created: [url: string]
}>()

const { t } = useI18n()
const { createGithubIssue } = useErrorReportAdmin()
const { error: showError, success: showSuccess } = useNotification()

const submitting = ref(false)
const isLoading = computed(() => submitting.value || props.loading === true)

const labelsHelp = computed(() => {
  const severity: ErrorReportSeverity = props.report.severity
  return t('error_report.github.labels_help', { severity: severity.toLowerCase() })
})

async function onCreate() {
  if (props.githubEnabled !== true) return
  submitting.value = true
  try {
    const res = await createGithubIssue(props.report.id)
    showSuccess(t('error_report.github.created_message'))
    emit('created', res.data.url)
  } catch (e) {
    console.error(e)
    showError(t('error_report.github.create_failed'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section
    class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
  >
    <header class="mb-3 flex items-center justify-between gap-2">
      <h3 class="text-base font-semibold">
        {{ t('error_report.github.tab') }}
      </h3>
    </header>

    <!-- 未設定 -->
    <div
      v-if="props.githubEnabled === false"
      class="rounded-md border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600"
    >
      <i class="pi pi-info-circle mr-1" aria-hidden="true" />
      {{ t('error_report.github.not_configured') }}
    </div>

    <!-- 未取得（取得待ち） -->
    <div
      v-else-if="props.githubEnabled === null"
      class="rounded-md border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600"
    >
      <i class="pi pi-spin pi-spinner mr-2" aria-hidden="true" />
      {{ t('error_report.github.loading') }}
    </div>

    <!-- 作成済 -->
    <div
      v-else-if="props.report.githubIssueUrl"
      class="space-y-3"
    >
      <p class="text-sm text-surface-700 dark:text-surface-200">
        <i class="pi pi-check-circle mr-1 text-green-600" aria-hidden="true" />
        {{ t('error_report.github.already_created') }}
      </p>
      <div class="flex flex-wrap items-center gap-2">
        <a
          :href="props.report.githubIssueUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-2 rounded-md border border-surface-300 px-3 py-1.5 text-sm font-medium hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-700"
        >
          <i class="pi pi-external-link" aria-hidden="true" />
          {{ t('error_report.github.view_issue') }}
        </a>
        <span class="break-all text-xs text-surface-500">
          {{ t('error_report.github.url_label') }}: {{ props.report.githubIssueUrl }}
        </span>
      </div>
    </div>

    <!-- 未作成 → 作成ボタン -->
    <div v-else class="space-y-3">
      <p class="text-sm text-surface-700 dark:text-surface-200">
        {{ t('error_report.github.create_description') }}
      </p>
      <p class="text-xs text-surface-500">{{ labelsHelp }}</p>
      <button
        type="button"
        class="inline-flex items-center gap-2 rounded-md border border-surface-300 px-3 py-1.5 text-sm font-medium hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-700 disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="isLoading"
        @click="onCreate"
      >
        <i
          :class="isLoading ? 'pi pi-spin pi-spinner' : 'pi pi-github'"
          aria-hidden="true"
        />
        <span v-if="isLoading">{{ t('error_report.github.creating') }}</span>
        <span v-else>{{ t('error_report.github.create_issue') }}</span>
      </button>
    </div>
  </section>
</template>
