<script setup lang="ts">
import type { SurveyDetailResponse } from '~/types/survey'

// i18n: surveys.detail.*
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const surveyId = Number(route.params.surveyId)
const rawScope = String(route.query.scope ?? '').toUpperCase()
const scopeType = (rawScope === 'TEAM' || rawScope === 'ORGANIZATION'
  ? rawScope
  : '') as 'TEAM' | 'ORGANIZATION' | ''
const scopeId = Number(route.query.scopeId)

const { getSurvey, publishSurvey, closeSurvey, deleteSurvey } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()
const { confirmAction } = useConfirmDialog()
const authStore = useAuthStore()

// scope / scopeId 欠落・不正な場合は即トップへ
if (!scopeType || !Number.isFinite(scopeId) || scopeId <= 0 || !Number.isFinite(surveyId)) {
  showError('アンケートのスコープ情報が不足しています')
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

/** 結果閲覧権限の判定 */
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

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    DRAFT: '下書き',
    PUBLISHED: '受付中',
    CLOSED: '締切',
  }
  return labels[status] ?? status
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
    showSuccess('アンケートを公開しました')
    await fetchDetail()
  } catch {
    showError('アンケートの公開に失敗しました')
  } finally {
    actionLoading.value = false
  }
}

function onCloseSurvey() {
  if (!survey.value) return
  confirmAction({
    header: 'アンケートを締切る',
    message: 'このアンケートを締切ります。締切後は新規回答を受け付けません。よろしいですか？',
    onAccept: async () => {
      actionLoading.value = true
      try {
        await closeSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
        showSuccess('アンケートを締切りました')
        await fetchDetail()
      } catch {
        showError('アンケートの締切に失敗しました')
      } finally {
        actionLoading.value = false
      }
    },
  })
}

function onDelete() {
  if (!survey.value) return
  confirmAction({
    header: 'アンケートを削除',
    message: 'このアンケートを削除します。この操作は取り消せません。よろしいですか？',
    onAccept: async () => {
      actionLoading.value = true
      try {
        await deleteSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
        showSuccess('アンケートを削除しました')
        await navigateTo(scopeListPath.value)
      } catch {
        showError('アンケートの削除に失敗しました')
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
  <div class="mx-auto max-w-3xl p-4">
    <BackButton :to="scopeListPath" />

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 取得失敗 -->
    <div
      v-else-if="fetchError || !survey"
      class="flex flex-col items-center gap-3 rounded-lg border border-red-200 bg-red-50 p-8 text-center dark:border-red-700 dark:bg-red-900/20"
    >
      <i class="pi pi-exclamation-triangle text-3xl text-red-500" />
      <p class="text-sm text-red-700 dark:text-red-200">アンケート情報を取得できませんでした</p>
      <Button label="戻る" icon="pi pi-arrow-left" outlined @click="navigateTo(scopeListPath)" />
    </div>

    <template v-else>
      <!-- ヘッダー -->
      <PageHeader :title="survey.title" size="sm">
        <span :class="statusClass(survey.status)" class="rounded px-2 py-0.5 text-xs font-medium">
          {{ statusLabel(survey.status) }}
        </span>
        <Badge
          v-if="survey.hasResponded"
          value="回答済み"
          severity="success"
        />
      </PageHeader>

      <!-- メタ情報 -->
      <div class="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-surface-500 dark:text-surface-400">
        <span v-if="survey.createdBy">
          <i class="pi pi-user mr-1" />{{ survey.createdBy.displayName }}
        </span>
        <span v-if="survey.deadline">
          <i class="pi pi-clock mr-1" />期限: {{ survey.deadline }}
        </span>
        <span>
          <i class="pi pi-users mr-1" />回答: {{ responseCountLabel }}
        </span>
        <span v-if="survey.isAnonymous" class="text-surface-400">
          <i class="pi pi-eye-slash mr-1" />匿名
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
          label="アンケートを締切る"
          icon="pi pi-times-circle"
          severity="warn"
          outlined
          :loading="actionLoading"
          @click="onCloseSurvey"
        />
      </div>

      <!-- モード別表示 -->
      <!-- DRAFT -->
      <div
        v-if="displayMode === 'draft'"
        class="rounded-lg border border-surface-200 bg-surface-50 p-6 dark:border-surface-700 dark:bg-surface-800"
      >
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          <i class="pi pi-info-circle mr-1" />
          このアンケートは下書き状態です。公開するまで回答を受け付けません。
        </p>
        <div v-if="isCreator || isAdminPlus" class="flex flex-wrap gap-2">
          <Button
            label="公開する"
            icon="pi pi-send"
            :loading="actionLoading"
            @click="onPublish"
          />
          <Button
            label="削除"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :loading="actionLoading"
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
        @submitted="onSubmitted"
      />

      <!-- 結果パネル -->
      <SurveyResultsPanel
        v-else-if="displayMode === 'results'"
        :survey-id="survey.id"
      />

      <!-- 結果非公開（締切＆権限なし） -->
      <div
        v-else-if="displayMode === 'closed-no-permission'"
        class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/60"
      >
        <i class="pi pi-lock text-3xl text-surface-400" />
        <p class="text-sm text-surface-500 dark:text-surface-300">
          このアンケートは締切られました。結果は公開されていません。
        </p>
      </div>
    </template>
  </div>
</template>
