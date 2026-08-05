<script setup lang="ts">
// F05.4 アンケート新規作成ダイアログ（ADHD配慮・二段フロー対応）
//
// 二段フロー: タイトルのみで下書き保存 → 詳細画面で設問追加 → 公開
// BE CreateSurveyRequest.questions は @NotEmpty なしのため、設問ゼロでDRAFT作成が可能。
// useFormDraft で入力途中を localStorage に自動保存し、ダイアログを閉じても復元する。

import type {
  CreateSurveyRequest,
  ResultsVisibility,
  SurveyResponse,
  UnrespondedVisibility,
} from '~/types/survey'
import type { QuestionDraft } from '~/components/survey/SurveyQuestionEditor.vue'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
}>()

// visible は defineModel が内部で 'update:visible' を発行する（仕様書の Emits 契約を満たす）
const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  created: [survey: SurveyResponse]
}>()

const { t } = useI18n()
const { createSurvey } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()
const { handleApiError } = useErrorHandler()
const authStore = useAuthStore()

// === フォーム状態 ===
const title = ref('')
const description = ref('')
const isAnonymous = ref(false)
const allowMultipleSubmissions = ref(false)
// F05.4 (B) チーム別内訳トグル（組織スコープのみ・匿名と相互排他）
const teamBreakdownEnabled = ref(false)
// 組織配信のときのみチーム別内訳トグルを表示する
const isOrganizationScope = computed(() => props.scopeType === 'ORGANIZATION')

// 匿名 ON のときはチーム別内訳を無効化（BE が SURVEY_023 で 400 を返すため UI で防ぐ）
watch(isAnonymous, (anon) => {
  if (anon) teamBreakdownEnabled.value = false
})
// チーム別内訳 ON のときは匿名を選べないようにする（相互排他）
watch(teamBreakdownEnabled, (enabled) => {
  if (enabled) isAnonymous.value = false
})
const resultsVisibility = ref<ResultsVisibility>('RESPONDENTS')
const unrespondedVisibility = ref<UnrespondedVisibility>('CREATOR_AND_ADMIN')
const deadline = ref<Date | null>(null)
const questions = ref<QuestionDraft[]>([])

const submitting = ref(false)

// === useFormDraft（ADHD配慮・自動保存）===
// キーにuserId・scopeType・scopeIdを含めてスコープ間の衝突を防ぐ
const draftKey = computed(
  () =>
    `survey-create-draft-${authStore.currentUser?.id ?? 'guest'}-${props.scopeType}-${props.scopeId}`,
)

// フォーム状態全体をオブジェクトとして型付けする
interface SurveyDraftShape {
  title: string
  description: string
  isAnonymous: boolean
  allowMultipleSubmissions: boolean
  teamBreakdownEnabled: boolean
  resultsVisibility: ResultsVisibility
  unrespondedVisibility: UnrespondedVisibility
  /**
   * 締切。これは localStorage の下書きスナップショット専用のキーであり、
   * BE へは送らない（送信時は `expiresAt` に載せ替える。BE に `deadline` は存在しない）。
   */
  deadline: string | null
  questions: QuestionDraft[]
}

// source にフォーム全体の computed を渡して自動保存
const formSnapshot = computed<SurveyDraftShape>(() => ({
  title: title.value,
  description: description.value,
  isAnonymous: isAnonymous.value,
  allowMultipleSubmissions: allowMultipleSubmissions.value,
  teamBreakdownEnabled: teamBreakdownEnabled.value,
  resultsVisibility: resultsVisibility.value,
  unrespondedVisibility: unrespondedVisibility.value,
  deadline: deadline.value ? deadline.value.toISOString() : null,
  questions: questions.value,
}))

const { clear: clearDraft, restore: restoreDraft, savedFlash } = useFormDraft<SurveyDraftShape>(
  draftKey.value,
  { source: formSnapshot, debounceMs: 1000, flashMs: 2000 },
)

// 復元済みフラグ（ダイアログを開いた直後に1回だけ復元トースト表示）
const restoredFlash = ref(false)

// ダイアログが開くたびにlocaleストレージから復元を試みる
watch(visible, (nowVisible) => {
  if (!nowVisible) return
  const saved = restoreDraft()
  if (saved) {
    title.value = saved.title ?? ''
    description.value = saved.description ?? ''
    isAnonymous.value = saved.isAnonymous ?? false
    allowMultipleSubmissions.value = saved.allowMultipleSubmissions ?? false
    teamBreakdownEnabled.value = saved.teamBreakdownEnabled ?? false
    resultsVisibility.value = saved.resultsVisibility ?? 'RESPONDENTS'
    unrespondedVisibility.value = saved.unrespondedVisibility ?? 'CREATOR_AND_ADMIN'
    deadline.value = saved.deadline ? new Date(saved.deadline) : null
    questions.value = saved.questions ?? []
    // 復元フラッシュ
    restoredFlash.value = true
    setTimeout(() => {
      restoredFlash.value = false
    }, 3000)
  }
})

// === 選択肢定義 ===
const resultsVisibilityOptions = computed<Array<{ label: string; value: ResultsVisibility }>>(() => [
  { label: t('surveys.resultsVisibility.CREATOR_ONLY'), value: 'CREATOR_ONLY' },
  { label: t('surveys.resultsVisibility.RESPONDENTS'), value: 'RESPONDENTS' },
  // NOTE: 'ALL_MEMBERS'（締切前から全員閲覧可）は BE の ResultsVisibility enum に対応値が無い。
  // BE が表現できる 'AFTER_CLOSE'（締切後に全員閲覧可）を提示する。
  { label: t('surveys.resultsVisibility.AFTER_CLOSE'), value: 'AFTER_CLOSE' },
])

const unrespondedVisibilityOptions = computed<Array<{ label: string; value: UnrespondedVisibility }>>(() => [
  { label: t('surveys.unrespondedVisibility.HIDDEN'), value: 'HIDDEN' },
  { label: t('surveys.unrespondedVisibility.CREATOR_AND_ADMIN'), value: 'CREATOR_AND_ADMIN' },
  { label: t('surveys.unrespondedVisibility.ALL_MEMBERS'), value: 'ALL_MEMBERS' },
])

// === 内部状態リセット ===
function resetForm() {
  title.value = ''
  description.value = ''
  isAnonymous.value = false
  allowMultipleSubmissions.value = false
  teamBreakdownEnabled.value = false
  resultsVisibility.value = 'RESPONDENTS'
  unrespondedVisibility.value = 'CREATOR_AND_ADMIN'
  deadline.value = null
  questions.value = []
}

function close() {
  // defineModel が 'update:visible' を自動 emit する
  visible.value = false
}

// === バリデーション ===
// mode: 'draft' = 設問不要、'publish' = 設問1つ以上必須
function validate(mode: 'draft' | 'publish'): string | null {
  if (!title.value.trim()) {
    return t('surveys.create.validation.titleRequired')
  }
  if (title.value.trim().length > 200) {
    return t('surveys.create.validation.titleTooLong')
  }
  if (description.value.length > 1000) {
    return t('surveys.create.validation.descriptionTooLong')
  }
  if (mode === 'publish' && questions.value.length === 0) {
    return t('surveys.create.validation.questionsRequired')
  }
  for (let i = 0; i < questions.value.length; i++) {
    const q = questions.value[i]
    if (!q) continue
    if (!q.questionText.trim()) {
      return t('surveys.create.validation.questionTextRequired', { index: i + 1 })
    }
    if (q.questionType === 'SINGLE_CHOICE' || q.questionType === 'MULTIPLE_CHOICE') {
      const opts = q.options ?? []
      if (opts.length < 2) {
        return t('surveys.create.validation.optionsTooFew', { index: i + 1 })
      }
      const hasEmpty = opts.some((o) => !o.optionText.trim())
      if (hasEmpty) {
        return t('surveys.create.validation.optionEmpty', { index: i + 1 })
      }
    }
  }
  return null
}

// === 送信共通処理 ===
async function submitWith(mode: 'draft' | 'publish') {
  const errorMsg = validate(mode)
  if (errorMsg) {
    showError(errorMsg)
    return
  }

  submitting.value = true
  try {
    const body: CreateSurveyRequest = {
      title: title.value.trim(),
      description: description.value.trim() || undefined,
      isAnonymous: isAnonymous.value,
      allowMultipleSubmissions: allowMultipleSubmissions.value,
      // 組織スコープのみチーム別内訳トグルを送る（チーム/個人スコープでは送らない）
      teamBreakdownEnabled: isOrganizationScope.value ? teamBreakdownEnabled.value : undefined,
      resultsVisibility: resultsVisibility.value,
      unrespondedVisibility: unrespondedVisibility.value,
      expiresAt: deadline.value ? deadline.value.toISOString() : undefined,
      // 設問ゼロの場合は空配列を送る（BEはDRAFTとして保存）
      questions:
        questions.value.length > 0
          ? questions.value.map((q) => ({
              questionText: q.questionText.trim(),
              questionType: q.questionType,
              isRequired: q.isRequired,
              sortOrder: q.sortOrder,
              options:
                q.questionType === 'TEXT' || q.questionType === 'DATE'
                  ? undefined
                  : (q.options ?? []).map((o) => ({
                      optionText: o.optionText.trim(),
                      sortOrder: o.sortOrder,
                    })),
            }))
          : [],
    }

    const res = await createSurvey(props.scopeType, props.scopeId, body)

    // 成功 → 下書き削除
    clearDraft()

    if (mode === 'draft') {
      showSuccess(t('surveys.create.draftSuccessToast'))
    } else {
      showSuccess(t('surveys.create.successToast'))
    }

    emit('created', res.data)
    resetForm()
    close()
  } catch (e) {
    // BE エラーコード（例: SURVEY_023 匿名×チーム別内訳の併用禁止）があれば
    // それを優先表示し、無ければ汎用の失敗トーストにフォールバックする。
    const apiError = e as { data?: { error?: { code?: string } } }
    if (apiError?.data?.error?.code) {
      handleApiError(e, 'surveyCreate')
    } else {
      showError(t('surveys.create.failureToast'))
    }
  } finally {
    submitting.value = false
  }
}

function submitDraft() {
  return submitWith('draft')
}

function submitAndPublish() {
  return submitWith('publish')
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :style="{ width: '720px' }"
    :breakpoints="{ '960px': '90vw' }"
    :header="t('surveys.create.dialogHeader')"
    @hide="resetForm"
  >
    <div class="flex flex-col gap-4" data-testid="survey-create-dialog">
      <!-- 復元フラッシュ -->
      <div
        v-if="restoredFlash"
        class="flex items-center gap-2 rounded-lg bg-blue-50 px-3 py-2 text-sm text-blue-700 dark:bg-blue-900/30 dark:text-blue-200"
        data-testid="survey-draft-restored-flash"
      >
        <i class="pi pi-history" />
        {{ t('surveys.create.draftRestored') }}
      </div>

      <!-- 下書き保存済みフラッシュ -->
      <div
        v-if="savedFlash"
        class="flex items-center gap-2 rounded-lg bg-surface-50 px-3 py-2 text-xs text-surface-500 dark:bg-surface-800 dark:text-surface-400"
        data-testid="survey-draft-saved-flash"
      >
        <i class="pi pi-save" />
        {{ t('surveys.create.draftSaved') }}
      </div>

      <!-- ADHD配慮ヒント: 設問は後で追加できる旨を案内 -->
      <div class="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-600 dark:bg-blue-900/20 dark:text-blue-300">
        <i class="pi pi-lightbulb mt-0.5 shrink-0" />
        <span>{{ t('surveys.create.draftHint') }}</span>
      </div>

      <!-- タイトル -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('surveys.create.title') }} <span class="text-red-500">*</span>
        </label>
        <InputText
          v-model="title"
          class="w-full"
          maxlength="200"
          :placeholder="t('surveys.create.titlePlaceholder')"
          autofocus
          data-testid="survey-create-title"
        />
      </div>

      <!-- 説明 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('surveys.create.description') }}</label>
        <Textarea
          v-model="description"
          class="w-full"
          rows="3"
          maxlength="1000"
          :placeholder="t('surveys.create.descriptionPlaceholder')"
          auto-resize
          data-testid="survey-create-description"
        />
      </div>

      <!-- オプション群 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="flex items-center gap-2 text-sm" data-testid="survey-create-anonymous">
          <Checkbox v-model="isAnonymous" binary />
          <span>{{ t('surveys.create.isAnonymous') }}</span>
        </label>
        <label class="flex items-center gap-2 text-sm" data-testid="survey-create-allow-multiple">
          <Checkbox v-model="allowMultipleSubmissions" binary />
          <span>{{ t('surveys.create.allowMultipleSubmissions') }}</span>
        </label>
      </div>

      <!-- F05.4 (B) チーム別内訳トグル（組織配信のみ・匿名と相互排他） -->
      <div v-if="isOrganizationScope" class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
        <label class="flex items-center gap-2 text-sm" data-testid="survey-create-team-breakdown">
          <Checkbox v-model="teamBreakdownEnabled" binary :disabled="isAnonymous" />
          <span :class="{ 'text-surface-400': isAnonymous }">
            {{ t('surveys.create.teamBreakdownEnabled') }}
          </span>
        </label>
        <p
          v-if="isAnonymous"
          class="mt-1 ml-6 text-xs text-surface-400"
          data-testid="survey-create-team-breakdown-anonymous-note"
        >
          <i class="pi pi-info-circle mr-1" />{{ t('surveys.create.teamBreakdownAnonymousConflict') }}
        </p>
        <p v-else class="mt-1 ml-6 text-xs text-surface-400">
          {{ t('surveys.create.teamBreakdownHint') }}
        </p>
      </div>

      <!-- 可視性設定 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('surveys.resultsVisibility.label') }}</label>
          <Select
            v-model="resultsVisibility"
            :options="resultsVisibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="survey-create-results-visibility"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('surveys.unrespondedVisibility.label') }}</label>
          <Select
            v-model="unrespondedVisibility"
            :options="unrespondedVisibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="survey-create-unresponded-visibility"
          />
        </div>
      </div>

      <!-- 締切 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('surveys.create.deadline') }}</label>
        <DatePicker
          v-model="deadline"
          class="w-full"
          show-time
          show-icon
          hour-format="24"
          date-format="yy/mm/dd"
          :placeholder="t('surveys.create.deadlinePlaceholder')"
          data-testid="survey-create-deadline"
        />
      </div>

      <!-- 設問エディタ（任意: 下書き保存は設問ゼロでも可） -->
      <div>
        <label class="mb-2 block text-sm font-medium">
          {{ t('surveys.create.questions') }}
        </label>
        <SurveyQuestionEditor v-model="questions" />
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('surveys.create.cancel')"
        text
        severity="secondary"
        :disabled="submitting"
        data-testid="survey-create-cancel"
        @click="close"
      />
      <!-- 下書きで保存: 設問不要、DRAFTとして作成 -->
      <Button
        :label="t('surveys.create.saveDraft')"
        icon="pi pi-save"
        severity="secondary"
        outlined
        :loading="submitting"
        data-testid="survey-create-save-draft"
        @click="submitDraft"
      />
      <!-- 公開して作成: 設問1つ以上必須 -->
      <Button
        :label="t('surveys.create.saveAndPublish')"
        icon="pi pi-send"
        :loading="submitting"
        data-testid="survey-create-submit"
        @click="submitAndPublish"
      />
    </template>
  </Dialog>
</template>
