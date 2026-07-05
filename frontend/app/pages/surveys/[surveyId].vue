<script setup lang="ts">
import type { SurveyDetailResponse } from '~/types/survey'
import type { BulletinThreadResponse } from '~/types/bulletin'
import type { QuestionDraft } from '~/components/survey/SurveyQuestionEditor.vue'
import SurveyRespondentsList from '~/components/survey/SurveyRespondentsList.vue'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const surveyId = Number(route.params.surveyId)
const rawScope = String(route.query.scope ?? '').toUpperCase()
const scopeType = (rawScope === 'TEAM' || rawScope === 'ORGANIZATION'
  ? rawScope
  : '') as 'TEAM' | 'ORGANIZATION' | ''
const scopeId = String(route.query.scopeId ?? '')

const { t } = useI18n()
const { getSurvey, publishSurvey, closeSurvey, deleteSurvey, addQuestion } = useSurveyApi()
const { getSurveyThread } = useSurveyBulletinThread()
const { error: showError, success: showSuccess } = useNotification()
const { confirmAction } = useConfirmDialog()
const authStore = useAuthStore()

// アンケートに紐づく掲示板スレッド（null = スレッド未生成 = 表示しない）
const bulletinThread = ref<BulletinThreadResponse | null>(null)

// scope / scopeId 欠落・不正な場合は即トップへ
if (!scopeType || !scopeId || !Number.isFinite(surveyId)) {
  showError(t('surveys.detail.scopeMissing'))
  await navigateTo('/')
}

// scopeId が確定してから RoleAccess をロード
const roleScope = scopeType === 'TEAM' ? 'team' : 'organization'
const scopeTypeStrict = scopeType as 'TEAM' | 'ORGANIZATION'
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess(roleScope, scopeId)

const survey = ref<SurveyDetailResponse['data'] | null>(null)
const loading = ref(true)
const fetchError = ref(false)
const actionLoading = ref(false)

// === DRAFTモード用: インライン設問追加 ===
// SurveyQuestionEditor は QuestionDraft[] を v-model で扱うため、
// DRAFT詳細画面でも同一エディタを再利用する。
// 「設問を保存して公開」ボタン押下時に addQuestion を順次呼び出してから publish する。
const draftQuestions = ref<QuestionDraft[]>([])
const draftQuestionsSubmitting = ref(false)

/** DRAFTの設問を一括保存 → publish する */
async function onSaveQuestionsAndPublish() {
  if (!survey.value) return
  draftQuestionsSubmitting.value = true
  try {
    // 設問を順次追加（BE addQuestion API を呼ぶ）
    for (const q of draftQuestions.value) {
      const beBody: Record<string, unknown> = {
        questionText: q.questionText.trim(),
        questionType: q.questionType,
        isRequired: q.isRequired,
        sortOrder: q.sortOrder,
      }
      if (q.questionType !== 'TEXT' && q.questionType !== 'DATE' && q.options && q.options.length > 0) {
        beBody.options = q.options.map((o) => ({
          optionText: o.optionText.trim(),
          sortOrder: o.sortOrder,
        }))
      }
      await addQuestion(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId, beBody)
    }
    // 公開
    await publishSurvey(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId)
    showSuccess(t('surveys.detail.publishSuccess'))
    draftQuestions.value = []
    await fetchDetail()
  } catch {
    showError(t('surveys.detail.publishFailed'))
  } finally {
    draftQuestionsSubmitting.value = false
  }
}

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
  return survey.value.audit?.createdBy === currentUserId.value
})

/** ADMIN+（ADMIN または SYSTEM_ADMIN）の判定 */
const isAdminPlus = computed(() => isAdmin.value)

/**
 * F05.4 (B) チーム別内訳パネルの表示ガード。
 *
 * 出欠側（EventDetailPanel の AttendanceTeamBreakdownPanel ガード = isAdminOrDeputy）および
 * BE 認可（checkAdminOrAbove = ADMIN/DEPUTY_ADMIN 許可）と判定を一致させる。
 * isAdminPlus（DEPUTY 除外）のままだと DEPUTY 組織管理者がアンケ内訳パネルだけ
 * 見られない過小露出（漏洩でなく UX 欠落）になるため DEPUTY を含める。
 * MEMBER/SUPPORTER/GUEST は引き続き非表示（漏洩を新たに作らない）。
 */
const canViewTeamBreakdown = computed(() => isAdminOrDeputy.value)

/** 回答者セクションの開閉状態（初期は閉じた状態） */
const showRespondents = ref(false)

/** 督促送信可否（ADMIN+ かつ 公開中のみ） */
const canRemind = computed(() => isAdminPlus.value && survey.value?.status === 'PUBLISHED')

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
  switch (s.policy?.resultsVisibility) {
    case 'CREATOR_ONLY':
      return false
    case 'RESPONDENTS':
      return (s as SurveyDetailResponse['data']).hasResponded === true
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
  // DRAFT は作成者・ADMIN+ 向けのプレビュー画面
  if (s.status === 'DRAFT') return 'draft'
  // 設計書 docs/features/F05.4_survey_vote.md L1377〜「結果閲覧権限の判定」に準拠:
  // 結果閲覧権限 (canViewResults) を持つユーザーは、回答可否より優先して結果画面を表示する。
  // ALL_MEMBERS（誰でも閲覧可）の場合、未回答 MEMBER も結果画面に直接遷移できる。
  if (canViewResults.value) return 'results'
  // 結果閲覧不可の場合のフォールバック分岐。
  // PUBLISHED: 未回答も回答済みも 'response'（SurveyResponseForm 側で「回答済み」表示へ）。
  if (s.status === 'PUBLISHED') return 'response'
  // CLOSED かつ結果閲覧権限なし → 非公開メッセージ。
  return 'closed-no-permission'
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
  if (s.stats?.targetCount && s.stats.targetCount > 0) {
    return `${s.stats.responseCount} / ${s.stats.targetCount}`
  }
  return String(s.stats?.responseCount ?? 0)
})

/** スコープ一覧画面のパス（戻り先） */
const scopeListPath = computed(() => {
  if (scopeType === 'TEAM') return `/teams/${scopeId}/surveys`
  if (scopeType === 'ORGANIZATION') return `/organizations/${scopeId}/surveys`
  return '/'
})

/**
 * 掲示板スレッドのページパス。
 * スレッドが存在する場合、そのスレッドが属するスコープの掲示板一覧ページへ遷移する。
 */
const bulletinThreadPath = computed(() => {
  if (!bulletinThread.value) return null
  const thread = bulletinThread.value
  const scope = thread.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${scope}/${thread.scopeId}/bulletin`
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
  await Promise.all([
    fetchDetail(),
    loadPermissions(),
    // 掲示板スレッド情報を取得（404 の場合は null のまま = 表示しない）
    getSurveyThread(surveyId).then((thread) => {
      bulletinThread.value = thread
    }),
  ])
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-4" data-testid="survey-detail-page">
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
      <PageHeader :title="survey.content?.title ?? ''" size="sm" :back-to="scopeListPath">
        <span :class="statusClass(survey.status)" class="rounded px-2 py-0.5 text-xs font-medium" data-testid="survey-detail-status">
          {{ t(`surveys.statusLabel.${survey.status}`) }}
        </span>
        <Badge
          v-if="(survey as SurveyDetailResponse['data']).hasResponded"
          :value="t('surveys.detail.answeredBadge')"
          severity="success"
        />
      </PageHeader>

      <!-- メタ情報 -->
      <div class="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-surface-500 dark:text-surface-400">
        <span v-if="survey.schedule?.expiresAt">
          <i class="pi pi-clock mr-1" />{{ t('surveys.detail.deadline') }}: {{ survey.schedule.expiresAt }}
        </span>
        <span>
          <i class="pi pi-users mr-1" />{{ t('surveys.detail.responseCount') }}: {{ responseCountLabel }}
        </span>
        <span v-if="survey.policy?.isAnonymous" class="text-surface-400">
          <i class="pi pi-eye-slash mr-1" />{{ t('surveys.detail.anonymous') }}
        </span>
      </div>

      <!-- 説明文 -->
      <p
        v-if="survey.content?.description"
        class="mb-6 whitespace-pre-line rounded-lg bg-surface-50 p-3 text-sm text-surface-700 dark:bg-surface-800 dark:text-surface-200"
      >
        {{ survey.content.description }}
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

      <!-- 掲示板スレッドリンク（スレッドが存在する場合のみ表示） -->
      <div v-if="bulletinThread && bulletinThreadPath" class="mb-4">
        <NuxtLink
          :to="bulletinThreadPath"
          class="inline-flex items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 px-4 py-2 text-sm font-medium text-surface-700 hover:bg-surface-100 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-200 dark:hover:bg-surface-700"
          data-testid="survey-bulletin-thread-link"
        >
          <i class="pi pi-comments" />
          {{ t('surveyPage.bulletinOpen') }}
        </NuxtLink>
      </div>

      <!-- モード別表示 -->
      <!-- DRAFT -->
      <div
        v-if="displayMode === 'draft'"
        class="flex flex-col gap-4"
        data-testid="survey-mode-draft"
      >
        <!-- ステータスバナー -->
        <div class="rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-700/40 dark:bg-amber-900/10">
          <p class="mb-2 text-sm text-amber-700 dark:text-amber-200">
            <i class="pi pi-info-circle mr-1" />
            {{ t('surveys.detail.draftHint') }}
          </p>
          <p class="text-xs text-amber-600 dark:text-amber-300">
            {{ t('surveys.detail.draftAddQuestionsHint') }}
          </p>
        </div>

        <!-- 作成者・ADMIN+: 設問追加 & 公開 -->
        <div v-if="isCreator || isAdminPlus">
          <!-- インライン設問エディタ -->
          <div class="mb-4 rounded-lg border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
            <p class="mb-3 text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ t('surveys.detail.draftQuestionsSection') }}
            </p>
            <SurveyQuestionEditor v-model="draftQuestions" />
          </div>

          <!-- 操作ボタン群 -->
          <div class="flex flex-wrap gap-2">
            <!-- 設問を追加して公開（設問が1つ以上あるときに強調） -->
            <Button
              v-if="draftQuestions.length > 0"
              :label="t('surveys.detail.publishButton')"
              icon="pi pi-send"
              :loading="draftQuestionsSubmitting"
              data-testid="survey-publish-with-questions-button"
              @click="onSaveQuestionsAndPublish"
            />
            <!-- 設問なしでそのまま公開（設問ゼロでも可、グレー強調） -->
            <Button
              :label="t('surveys.detail.publishButton')"
              icon="pi pi-send"
              :severity="draftQuestions.length > 0 ? 'secondary' : 'primary'"
              :outlined="draftQuestions.length > 0"
              :loading="actionLoading"
              data-testid="survey-publish-button"
              @click="onPublish"
            />
            <Button
              :label="t('surveys.detail.deleteButton')"
              icon="pi pi-trash"
              severity="danger"
              outlined
              :loading="actionLoading || draftQuestionsSubmitting"
              data-testid="survey-delete-button"
              @click="onDelete"
            />
          </div>
        </div>
      </div>

      <!-- 回答フォーム -->
      <SurveyResponseForm
        v-else-if="displayMode === 'response'"
        :survey="survey"
        :already-responded="(survey as SurveyDetailResponse['data']).hasResponded ?? false"
        :allow-multiple="survey.policy?.allowMultipleSubmissions ?? false"
        data-testid="survey-mode-response"
        @submitted="onSubmitted"
      />

      <!-- 結果パネル -->
      <!--
        NOTE: SurveyResultsPanel の root には `data-testid="survey-results-panel"` が付与されている。
        ここで `data-testid="survey-mode-results"` を渡すと Vue 3 の attribute fallthrough で
        root に上書きマージされて子の testid が消える。両方 testid を残すために
        wrapper の `<div>` で分離する。
      -->
      <div
        v-else-if="displayMode === 'results'"
        data-testid="survey-mode-results"
      >
        <SurveyResultsPanel :survey-id="survey.id" />
      </div>

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

      <!-- F05.4 (B) チーム別内訳（組織スコープ + ADMIN/DEPUTY_ADMIN）。
           認可は BE 側 org-ADMIN+ 限定 EP。ここでは出し分けの一次フィルタとして
           組織スコープ + canViewTeamBreakdown（出欠側・BE 認可と一致した DEPUTY 含む判定）を
           要求する（403 時はパネル内で明示表示）。 -->
      <section
        v-if="canViewTeamBreakdown && scopeType === 'ORGANIZATION'"
        class="mt-6"
        data-testid="survey-team-breakdown-section"
      >
        <Card>
          <template #content>
            <SurveyTeamBreakdownPanel :survey-id="survey.id" />
          </template>
        </Card>
      </section>

      <!-- 回答者セクション（作成者 or ADMIN+ のみ） -->
      <section
        v-if="isAdminPlus || isCreator"
        data-testid="survey-respondents-section"
        class="mt-6"
      >
        <Card>
          <template #title>
            <div class="flex items-center justify-between">
              <span>{{ t('surveys.detail.respondentsSection.title') }}</span>
              <Button
                :label="showRespondents ? t('surveys.detail.respondentsSection.toggleHide') : t('surveys.detail.respondentsSection.toggleShow')"
                :icon="showRespondents ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
                text
                size="small"
                data-testid="survey-respondents-toggle"
                @click="showRespondents = !showRespondents"
              />
            </div>
          </template>
          <template #content>
            <SurveyRespondentsList
              v-if="showRespondents"
              :scope-type="scopeTypeStrict"
              :scope-id="scopeId"
              :survey-id="surveyId"
              :can-remind="canRemind"
            />
          </template>
        </Card>
      </section>
    </template>
  </div>
</template>
