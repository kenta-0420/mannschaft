<script setup lang="ts">
import type { SurveyDetailResponse } from '~/types/survey'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const surveyId = Number(route.params.surveyId)
const rawScope = String(route.query.scope ?? '').toUpperCase()
const scopeType = (rawScope === 'TEAM' || rawScope === 'ORGANIZATION'
  ? rawScope
  : '') as 'TEAM' | 'ORGANIZATION' | ''
const scopeId = Number(route.query.scopeId)

const { t } = useI18n()
const { getSurvey, publishSurvey, closeSurvey, deleteSurvey } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()
const { confirmAction } = useConfirmDialog()
const authStore = useAuthStore()

// scope / scopeId 欠落・不正な場合は即トップへ
if (!scopeType || !Number.isFinite(scopeId) || scopeId <= 0 || !Number.isFinite(surveyId)) {
  showError(t('surveys.detail.scopeMissing'))
  await navigateTo('/')
}

// scopeId が確定してから RoleAccess をロード
const roleScope = scopeType === 'TEAM' ? 'team' : 'organization'
const { isAdmin, loadPermissions } = useRoleAccess(roleScope, scopeId)

const survey = ref<SurveyDetailResponse['data'] | null>(null)
const loading = ref(true)
const fetchError = ref(false)
const actionLoading = ref(false)

const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

async function fetchDetail() {
  loading.value = true
  fetchError.value = false
  try {
    const res = await getSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
    survey.value = res.data
  } catch {
    fetchError.value = true
  } finally {
    loading.value = false
  }
}

const isCreator = computed(() => {
  if (!survey.value || currentUserId.value === null) return false
  return survey.value.createdBy?.id === currentUserId.value
})

/** ADMIN+（ADMIN または SYSTEM_ADMIN）の判定 */
const isAdminPlus = computed(() => isAdmin.value)

/**
 * 結果閲覧権限の判定。
 *
 * 設計書 docs/features/F05.4_survey_vote.md §権限判定 (L1377〜) に準拠。
 * AFTER_CLOSE は status='CLOSED' のときのみ全員に閲覧解放する。
 */
const canViewResults = computed(() => {
  const s = survey.value
  if (!s) return false
  if (isCreator.value) return true
  if (isAdminPlus.value) return true
  switch (s.resultsVisibility) {
    case 'CREATOR_ONLY':
      return false
    case 'RESPONDENTS':
      return s.hasResponded === true
    case 'ALL_MEMBERS':
      return true
    case 'AFTER_CLOSE':
      return s.status === 'CLOSED'
    default:
      return false
  }
})

/** 表示モード判定 */
type DisplayMode = 'response' | 'results' | 'closed-no-permission' | 'draft'
const displayMode = computed<DisplayMode>(() => {
  const s = survey.value
  if (!s) return 'response'
  if (s.status === 'DRAFT') return 'draft'
  if (s.status === 'PUBLISHED') {
    // 仕様: 未回答 or 複数回答可なら回答画面（最優先）
    if (!s.hasResponded || s.allowMultipleSubmissions) return 'response'
    // 回答済み・複数回答不可: 結果閲覧権限があれば結果、なければ「回答済み」表示（response 内で出る）
    return canViewResults.value ? 'results' : 'response'
  }
  // CLOSED: 結果閲覧権限があれば結果、なければ非公開メッセージ
  return canViewResults.value ? 'results' : 'closed-no-permission'
})

function statusClass(status: string): string {
  switch (status) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-200'
    case 'PUBLISHED':
      return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-200'
    case 'CLOSED':
      return 'bg-red-100 text-red-600 dark:bg-red-900/40 dark:text-red-200'
    default:
      return 'bg-surface-100 text-surface-600'
  }
}

const responseCountLabel = computed(() => {
  const s = survey.value
  if (!s) return ''
  if (s.targetCount && s.targetCount > 0) {
    return `${s.responseCount} / ${s.targetCount}`
  }
  return String(s.responseCount)
})

/** スコープ一覧画面のパス（戻り先） */
const scopeListPath = computed(() => {
  if (scopeType === 'TEAM') return `/teams/${scopeId}/surveys`
  if (scopeType === 'ORGANIZATION') return `/organizations/${scopeId}/surveys`
  return '/'
})

async function onPublish() {
  if (!survey.value) return
  actionLoading.value = true
  try {
    await publishSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
    showSuccess(t('surveys.detail.publishSuccess'))
    await fetchDetail()
  } catch {
    showError(t('surveys.detail.publishFailed'))
  } finally {
    actionLoading.value = false
  }
}

function onCloseSurvey() {
  if (!survey.value) return
  confirmAction({
    header: t('surveys.detail.closeConfirmHeader'),
    message: t('surveys.detail.closeConfirmMessage'),
    onAccept: async () => {
      actionLoading.value = true
      try {
        await closeSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
        showSuccess(t('surveys.detail.closeSuccess'))
        await fetchDetail()
      } catch {
        showError(t('surveys.detail.closeFailed'))
      } finally {
        actionLoading.value = false
      }
    },
  })
}

function onDelete() {
  if (!survey.value) return
  confirmAction({
    header: t('surveys.detail.deleteConfirmHeader'),
    message: t('surveys.detail.deleteConfirmMessage'),
    onAccept: async () => {
      actionLoading.value = true
      try {
        await deleteSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
        showSuccess(t('surveys.detail.deleteSuccess'))
        await navigateTo(scopeListPath.value)
      } catch {
        showError(t('surveys.detail.deleteFailed'))
        actionLoading.value = false
      }
    },
  })
}

async function onSubmitted() {
  // 回答送信成功 → 詳細を再取得して表示モードを更新
  await fetchDetail()
}

onMounted(async () => {
  await Promise.all([fetchDetail(), loadPermissions()])
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-4" data-testid="survey-detail-page">
    <BackButton :to="scopeListPath" />

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 取得失敗 -->
    <div
      v-else-if="fetchError || !survey"
      class="flex flex-col items-center gap-3 rounded-lg border border-red-200 bg-red-50 p-8 text-center dark:border-red-700 dark:bg-red-900/20"
    >
      <i class="pi pi-exclamation-triangle text-3xl text-red-500" />
      <p class="text-sm text-red-700 dark:text-red-200">{{ t('surveys.detail.fetchFailed') }}</p>
      <Button :label="t('surveys.detail.back')" icon="pi pi-arrow-left" outlined @click="navigateTo(scopeListPath)" />
    </div>

    <template v-else>
      <!-- ヘッダー -->
      <PageHeader :title="survey.title" size="sm">
        <span :class="statusClass(survey.status)" class="rounded px-2 py-0.5 text-xs font-medium" data-testid="survey-detail-status">
          {{ t(`surveys.statusLabel.${survey.status}`) }}
        </span>
        <Badge
          v-if="survey.hasResponded"
          :value="t('surveys.detail.answeredBadge')"
          severity="success"
        />
      </PageHeader>

      <!-- メタ情報 -->
      <div class="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-surface-500 dark:text-surface-400">
        <span v-if="survey.createdBy">
          <i class="pi pi-user mr-1" />{{ survey.createdBy.displayName }}
        </span>
        <span v-if="survey.deadline">
          <i class="pi pi-clock mr-1" />{{ t('surveys.detail.deadline') }}: {{ survey.deadline }}
        </span>
        <span>
          <i class="pi pi-users mr-1" />{{ t('surveys.detail.responseCount') }}: {{ responseCountLabel }}
        </span>
        <span v-if="survey.isAnonymous" class="text-surface-400">
          <i class="pi pi-eye-slash mr-1" />{{ t('surveys.detail.anonymous') }}
        </span>
      </div>

      <!-- 説明文 -->
      <p
        v-if="survey.description"
        class="mb-6 whitespace-pre-line rounded-lg bg-surface-50 p-3 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
      >
        {{ survey.description }}
      </p>

      <!-- 操作ボタン群（作成者 or ADMIN+） -->
      <div
        v-if="(isCreator || isAdminPlus) && (survey.status === 'PUBLISHED' || survey.status === 'DRAFT')"
        class="mb-4 flex flex-wrap gap-2"
      >
        <Button
          v-if="survey.status === 'PUBLISHED'"
          :label="t('surveys.detail.closeButton')"
          icon="pi pi-times-circle"
          severity="warn"
          outlined
          :loading="actionLoading"
          data-testid="survey-close-button"
          @click="onCloseSurvey"
        />
      </div>

      <!-- モード別表示 -->
      <!-- DRAFT -->
      <div
        v-if="displayMode === 'draft'"
        class="rounded-lg border border-surface-200 bg-surface-50 p-6 dark:border-surface-700 dark:bg-surface-800"
        data-testid="survey-mode-draft"
      >
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          <i class="pi pi-info-circle mr-1" />
          {{ t('surveys.detail.draftHint') }}
        </p>
        <div v-if="isCreator || isAdminPlus" class="flex flex-wrap gap-2">
          <Button
            :label="t('surveys.detail.publishButton')"
            icon="pi pi-send"
            :loading="actionLoading"
            data-testid="survey-publish-button"
            @click="onPublish"
          />
          <Button
            :label="t('surveys.detail.deleteButton')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :loading="actionLoading"
            data-testid="survey-delete-button"
            @click="onDelete"
          />
        </div>
      </div>

      <!-- 回答フォーム -->
      <SurveyResponseForm
        v-else-if="displayMode === 'response'"
        :survey="survey"
        :already-responded="survey.hasResponded"
        :allow-multiple="survey.allowMultipleSubmissions"
        data-testid="survey-mode-response"
        @submitted="onSubmitted"
      />

      <!-- 結果パネル -->
      <SurveyResultsPanel
        v-else-if="displayMode === 'results'"
        :survey-id="survey.id"
        data-testid="survey-mode-results"
      />

      <!-- 結果非公開（締切＆権限なし） -->
      <div
        v-else-if="displayMode === 'closed-no-permission'"
        class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/60"
        data-testid="survey-mode-closed-no-permission"
      >
        <i class="pi pi-lock text-3xl text-surface-400" />
        <p class="text-sm text-surface-500 dark:text-surface-300">
          {{ t('surveys.detail.closedNoPermission') }}
        </p>
      </div>
    </template>
  </div>
</template>
