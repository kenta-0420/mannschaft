<script setup lang="ts">
import type { SurveyDetailResponse } from '~/types/survey'
import type { BulletinThreadResponse } from '~/types/bulletin'
import type { QuestionDraft } from '~/components/survey/SurveyQuestionEditor.vue'
import SurveyRespondentsList from '~/components/survey/SurveyRespondentsList.vue'
import {
  isResultWithheldForAnonymityPrivacy,
  MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS,
} from '~/utils/surveyResultPrivacy'
import {
  resolveSurveyDisplayMode,
  shouldShowRespondCta,
  type SurveyDisplayMode,
} from '~/utils/surveyDisplayMode'
import {
  canManageSurvey,
  canRemindSurvey,
  canViewSurveyTeamBreakdown,
} from '~/utils/surveyViewerCapabilities'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const surveyId = Number(route.params.surveyId)
const rawScope = String(route.query.scope ?? '').toUpperCase()
const scopeType = (rawScope === 'TEAM' || rawScope === 'ORGANIZATION'
  ? rawScope
  : '') as 'TEAM' | 'ORGANIZATION' | ''
const scopeId = String(route.query.scopeId ?? '')

const { t } = useI18n()
const { getSurvey, publishSurvey, closeSurvey, deleteSurvey, addQuestion } =
  useSurveyApi()
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

const scopeTypeStrict = scopeType as 'TEAM' | 'ORGANIZATION'

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
    // 設問を順次追加。FE ドメイン形のまま渡し、BE 形への翻訳（questionType の enum 値・
    // sortOrder → displayOrder）は useSurveyApi 側に集約している。
    for (const q of draftQuestions.value) {
      await addQuestion(scopeType as 'TEAM' | 'ORGANIZATION', scopeId, surveyId, {
        questionText: q.questionText.trim(),
        questionType: q.questionType,
        isRequired: q.isRequired,
        sortOrder: q.sortOrder,
        options:
          q.questionType !== 'TEXT' && q.questionType !== 'DATE' && q.options?.length
            ? q.options.map((o) => ({ optionText: o.optionText.trim(), sortOrder: o.sortOrder }))
            : undefined,
      })
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

/**
 * 管理操作を行えるか（CMP-041）。
 *
 * 定義は BE と同一で「**作成者 または ADMIN／MANAGE_SURVEYS を持つ DEPUTY_ADMIN**」である。
 * かつてはここで FE のロール判定（役職名だけを見る composable）を使い、コメントにも
 * 「ADMIN+（ADMIN または SYSTEM_ADMIN）」という **BE 仕様とは異なる定義**を書いていた。
 * BE が管理操作を MANAGE_SURVEYS 保有 DEPUTY_ADMIN へ委任した結果、その判定のままでは
 * 「権限を持たない副管理者にボタンは見えるが、押すと 403」という状態になる。
 *
 * よって FE は認可ロジックを持たず、詳細応答の `viewerCanManage` に従う。この値は管理系 API が
 * 403 を投げるのと**同じ判定点**（`SurveyAccessGuard#canManage`）から得ている
 * （先例: `viewerCanViewResults`・Issue #2779）。
 *
 * 欠けている応答は fail-closed（操作させない）に倒す。
 */
const canManage = computed(() => canManageSurvey(survey.value))

/**
 * F05.4 (B) チーム別内訳パネルの表示ガード。
 *
 * チーム別内訳は組織の管理ビューであり、BE（SurveyResultService#getTeamBreakdown）は
 * **作成者高速パスを持たず** ADMIN／MANAGE_SURVEYS 保有 DEPUTY_ADMIN のみを通す。
 * したがって canManage（作成者を含む）ではなく、専用の viewerCanViewTeamBreakdown に従う。
 * MEMBER/SUPPORTER/GUEST は引き続き非表示（漏洩を新たに作らない）。こちらも fail-closed。
 */
const canViewTeamBreakdown = computed(() => canViewSurveyTeamBreakdown(survey.value))

/** 回答者セクションの開閉状態（初期は閉じた状態） */
const showRespondents = ref(false)

/** 督促送信可否（管理操作可 かつ 公開中のみ。BE: SurveyRemindService#remind と同一粒度） */
const canRemind = computed(() => canRemindSurvey(survey.value, survey.value?.status))

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
  if (canManage.value) return true
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

/**
 * 匿名＋リアルタイム公開かつ少数回答のとき、集計結果を伏せるか。
 *
 * 設計書 docs/features/F05.4_survey_vote.md §6 セキュリティ考慮事項の
 * 「匿名 + リアルタイム結果のプライバシー制限」に準拠（判定と閾値は
 * utils/surveyResultPrivacy.ts に集約。閾値は将来調整可能）。
 *
 * 権限（canViewResults）とは別軸のガードである。権限があっても、少数回答の匿名アンケートでは
 * 「自分が回答した直後の集計の動き」から他人の回答が推測できてしまうため伏せる。
 */
const resultsWithheldForPrivacy = computed(() =>
  isResultWithheldForAnonymityPrivacy({
    isAnonymous: survey.value?.policy?.isAnonymous ?? false,
    resultsVisibility: survey.value?.policy?.resultsVisibility,
    responseCount: survey.value?.stats?.responseCount ?? 0,
  }),
)

/**
 * 自分が回答済みか。
 *
 * SurveyResponseForm へ `already-responded` として渡している既存の判定をそのまま使う
 * （新しい仕組みを作らない）。実 BE は hasResponded を返さないため、useSurveyApi が
 * 「自分の回答」の有無から導出して詰めている。
 */
const hasResponded = computed(
  () => (survey.value as SurveyDetailResponse['data'] | null)?.hasResponded ?? false,
)

/**
 * サーバーが結果閲覧を拒否するか。
 *
 * `canViewResults` は `ALL_MEMBERS` を無条件に真とする FE の楽観判定だが、BE は `ALWAYS` の
 * 閲覧範囲を配信母集団に限定している（設計書 L107-112）。`TARGETED` の名簿外や
 * `includeSupporters=false` で除外された SUPPORTER には、結果パネルと回答導線が出るのに
 * BE が拒否する「押せるのに必ず失敗する導線」が出てしまう。
 *
 * Issue #2779: 以前は結果取得を1回余分に叩き 403 かどうかで判定していた（403 プローブ）。
 * 現在は詳細応答の `viewerCanViewResults` を見る。この値は BE が 403 を投げるのと
 * **同じ判定点**から得ているため、プローブと結果が一致する。
 *
 * **フラグが欠けている応答は fail-closed（不可視）に倒す。** `true` のときだけ可視とし、
 * `false` も `undefined` も `null` も拒否として扱う。
 *
 * かつては「明示的な `false` のときだけ拒否」という寛容な判定にしていたが、それが正しかったのは
 * **403 プローブという裏付けがあった時代**である。プローブは実際に結果取得を叩いて 403 を見ていたので、
 * フラグが無くても判断材料そのものは存在した。プローブを撤去した今、フラグが欠けた応答には
 * **判断材料が一つも無い**。材料が無いまま許可へ倒せば、配信対象外の利用者にも結果パネルと
 * 回答導線が出て「押せるのに必ず失敗する導線」が復活する。
 *
 * このリポジトリの可視性は fail-closed が原則であり、`viewerCanViewResults` は BE が必ず設定する
 * 契約になった。よって欠けている応答は異常であり、異常時に許可へ倒すのは誤りである。
 * ただし過度に神経質な表示（エラー扱い・再試行導線）にはせず、単に見せないだけに留める。
 */
const resultsForbidden = computed(
  () => (survey.value as SurveyDetailResponse['data'] | null)?.viewerCanViewResults !== true,
)

/** 回答フォームへ移る。 */
function goToResponseForm() {
  responseRequested.value = true
}

/**
 * 結果画面の回答導線が押されたか。
 *
 * ALL_MEMBERS は「未回答 MEMBER も結果画面に直接遷移できる」のが仕様のため、
 * 結果画面を出したうえで、そこから回答フォームへ移れるようにする。
 */
const responseRequested = ref(false)

/** 結果画面に回答導線を出すか（未回答、または複数回答可で回答済み）。 */
const showRespondCta = computed(() =>
  shouldShowRespondCta({
    status: survey.value?.status,
    hasResponded: hasResponded.value,
    allowMultipleSubmissions: survey.value?.policy?.allowMultipleSubmissions ?? false,
  }),
)

/** 表示モード判定（優先順位は utils/surveyDisplayMode.ts の純関数に集約） */
const displayMode = computed<SurveyDisplayMode>(() => {
  const s = survey.value
  if (!s) return 'response'
  return resolveSurveyDisplayMode({
    status: s.status,
    canViewResults: canViewResults.value,
    resultsWithheldForPrivacy: resultsWithheldForPrivacy.value,
    hasResponded: hasResponded.value,
    allowMultipleSubmissions: s.policy?.allowMultipleSubmissions ?? false,
    responseRequested: responseRequested.value,
    resultsForbidden: resultsForbidden.value,
  })
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
  // 回答送信成功 → 結果画面へ戻し、詳細を再取得して表示モードを更新
  responseRequested.value = false
  await fetchDetail()
}

onMounted(async () => {
  await Promise.all([
    fetchDetail(),
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

      <!-- 操作ボタン群（BE の管理操作認可と同一判定 = viewerCanManage） -->
      <div
        v-if="canManage && (survey.status === 'PUBLISHED' || survey.status === 'DRAFT')"
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

        <!-- 管理操作可（作成者 or 権限保有管理者）: 設問追加 & 公開 -->
        <div v-if="canManage">
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
              :loading="actionLoading || draftQuestionsSubmitting"
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
        :already-responded="hasResponded"
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
        <!-- ALL_MEMBERS は未回答者も結果画面に直接来るため、ここから回答できる導線が要る。
             これが無いと未回答者が結果画面に固定され、UI から回答を集めきれない。 -->
        <div
          v-if="showRespondCta"
          class="mb-4 flex justify-end"
        >
          <Button
            :label="
              hasResponded
                ? t('surveys.results.editResponseCta')
                : t('surveys.results.respondCta')
            "
            icon="pi pi-pencil"
            data-testid="survey-respond-cta"
            @click="goToResponseForm"
          />
        </div>
        <SurveyResultsPanel :survey-id="survey.id" />
      </div>

      <!-- 匿名＋リアルタイム＋少数回答のプライバシーガード（設計書 §6）。
           権限はあるが集計を伏せる状態。黙って空にせず理由を明示する。 -->
      <div
        v-else-if="displayMode === 'results-withheld-privacy'"
        class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/60"
        data-testid="survey-mode-results-withheld-privacy"
      >
        <i class="pi pi-shield text-3xl text-surface-400" />
        <p class="text-sm font-medium text-surface-700 dark:text-surface-100">
          {{ t('surveys.results.withheldForPrivacy.title') }}
        </p>
        <p class="text-sm text-surface-500 dark:text-surface-300">
          {{
            t('surveys.results.withheldForPrivacy.description', {
              threshold: MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS,
              count: survey.stats?.responseCount ?? 0,
            })
          }}
        </p>
        <!-- 集計は伏せていても回答（および複数回答可なら修正）はできる -->
        <Button
          v-if="showRespondCta"
          :label="
            hasResponded ? t('surveys.results.editResponseCta') : t('surveys.results.respondCta')
          "
          icon="pi pi-pencil"
          class="mt-2"
          data-testid="survey-respond-cta"
          @click="goToResponseForm"
        />
      </div>

      <!-- 配信対象外（サーバーが結果閲覧を拒否）。
           FE の楽観判定で結果パネルや回答導線を出すと「押せるのに必ず失敗する」ため、
           黙って空にせず権限が無いことを明示する。 -->
      <div
        v-else-if="displayMode === 'results-forbidden'"
        class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/60"
        data-testid="survey-mode-results-forbidden"
      >
        <i class="pi pi-lock text-3xl text-surface-400" />
        <p class="text-sm font-medium text-surface-700 dark:text-surface-100">
          {{ t('surveys.results.forbidden.title') }}
        </p>
        <p class="text-sm text-surface-500 dark:text-surface-300">
          {{ t('surveys.results.forbidden.description') }}
        </p>
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

      <!-- F05.4 (B) チーム別内訳（組織スコープ + ADMIN／MANAGE_SURVEYS 保有 DEPUTY_ADMIN）。
           認可は BE 側 org-ADMIN+ 限定 EP。ここでは出し分けの一次フィルタとして
           組織スコープ + canViewTeamBreakdown（BE の内訳 EP 認可と同一の viewerCanViewTeamBreakdown）を
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

      <!-- 回答者セクション（管理操作可のみ。BE: SurveyResponseService の認可と同一粒度） -->
      <section
        v-if="canManage"
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
