<script setup lang="ts">
// F05.4 アンケート新規作成ダイアログ
// - PrimeVue Dialog で全項目を1ページに集約
// - 設問編集は SurveyQuestionEditor を v-model で組み込み
// - 簡易バリデーションを通過後 useSurveyApi().createSurvey を呼び出す
// i18n: surveys.create.* （第三陣Dで置換予定）

import type {
  CreateSurveyRequest,
  ResultsVisibility,
  SurveyResponse,
  UnrespondedVisibility,
} from '~/types/survey'
import type { QuestionDraft } from '~/components/survey/SurveyQuestionEditor.vue'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

// visible は defineModel が内部で 'update:visible' を発行する（仕様書の Emits 契約を満たす）
const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  created: [survey: SurveyResponse]
}>()

const { createSurvey } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()

// === フォーム状態 ===
const title = ref('')
const description = ref('')
const isAnonymous = ref(false)
const allowMultipleSubmissions = ref(false)
const resultsVisibility = ref<ResultsVisibility>('RESPONDENTS')
const unrespondedVisibility = ref<UnrespondedVisibility>('CREATOR_AND_ADMIN')
const deadline = ref<Date | null>(null)
const questions = ref<QuestionDraft[]>([])

const submitting = ref(false)

// === 選択肢定義 ===
const resultsVisibilityOptions: Array<{ label: string; value: ResultsVisibility }> = [
  { label: '作成者のみ', value: 'CREATOR_ONLY' }, // i18n: surveys.create.resultsVisibility.creatorOnly
  { label: '回答者のみ', value: 'RESPONDENTS' }, // i18n: surveys.create.resultsVisibility.respondents
  { label: '全メンバー', value: 'ALL_MEMBERS' }, // i18n: surveys.create.resultsVisibility.allMembers
]

const unrespondedVisibilityOptions: Array<{ label: string; value: UnrespondedVisibility }> = [
  { label: '非表示', value: 'HIDDEN' }, // i18n: surveys.create.unrespondedVisibility.hidden
  { label: '作成者と管理者のみ', value: 'CREATOR_AND_ADMIN' }, // i18n: surveys.create.unrespondedVisibility.creatorAndAdmin
  { label: '全メンバー', value: 'ALL_MEMBERS' }, // i18n: surveys.create.unrespondedVisibility.allMembers
]

// === 内部状態リセット ===
function resetForm() {
  title.value = ''
  description.value = ''
  isAnonymous.value = false
  allowMultipleSubmissions.value = false
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
function validate(): string | null {
  // i18n: surveys.create.validation.*
  if (!title.value.trim()) {
    return 'タイトルを入力してください'
  }
  if (title.value.trim().length > 200) {
    return 'タイトルは200文字以内で入力してください'
  }
  if (description.value.length > 1000) {
    return '説明は1000文字以内で入力してください'
  }
  if (questions.value.length === 0) {
    return '設問を1つ以上追加してください'
  }
  for (let i = 0; i < questions.value.length; i++) {
    const q = questions.value[i]
    if (!q) continue
    if (!q.questionText.trim()) {
      return `設問${i + 1}の文章を入力してください`
    }
    if (q.questionType === 'SINGLE_CHOICE' || q.questionType === 'MULTIPLE_CHOICE') {
      const opts = q.options ?? []
      if (opts.length < 2) {
        return `設問${i + 1}の選択肢は2つ以上必要です`
      }
      const hasEmpty = opts.some((o) => !o.optionText.trim())
      if (hasEmpty) {
        return `設問${i + 1}の選択肢に空欄があります`
      }
    }
  }
  return null
}

// === 保存 ===
async function submit() {
  const errorMsg = validate()
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
      resultsVisibility: resultsVisibility.value,
      unrespondedVisibility: unrespondedVisibility.value,
      deadline: deadline.value ? deadline.value.toISOString() : undefined,
      questions: questions.value.map((q) => ({
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
      })),
    }

    const res = await createSurvey(
      props.scopeType,
      props.scopeId,
      body as unknown as Record<string, unknown>,
    )
    showSuccess('アンケートを作成しました')
    // i18n: surveys.create.successToast
    emit('created', res.data)
    resetForm()
    close()
  } catch {
    showError('アンケートの作成に失敗しました')
    // i18n: surveys.create.failureToast
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :style="{ width: '720px' }"
    :breakpoints="{ '960px': '90vw' }"
    header="アンケートを作成"
    @hide="resetForm"
  >
    <!-- i18n: surveys.create.dialogHeader -->
    <div class="flex flex-col gap-4">
      <!-- タイトル -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          タイトル <span class="text-red-500">*</span>
          <!-- i18n: surveys.create.title -->
        </label>
        <InputText
          v-model="title"
          class="w-full"
          maxlength="200"
          placeholder="例: 次回イベントの希望日アンケート"
          autofocus
        />
      </div>

      <!-- 説明 -->
      <div>
        <label class="mb-1 block text-sm font-medium">説明（任意）</label>
        <!-- i18n: surveys.create.description -->
        <Textarea
          v-model="description"
          class="w-full"
          rows="3"
          maxlength="1000"
          placeholder="アンケートの目的や注意事項を記載してください"
          auto-resize
        />
      </div>

      <!-- オプション群 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="flex items-center gap-2 text-sm">
          <Checkbox v-model="isAnonymous" binary />
          <span>匿名回答にする</span>
          <!-- i18n: surveys.create.isAnonymous -->
        </label>
        <label class="flex items-center gap-2 text-sm">
          <Checkbox v-model="allowMultipleSubmissions" binary />
          <span>複数回の回答を許可</span>
          <!-- i18n: surveys.create.allowMultipleSubmissions -->
        </label>
      </div>

      <!-- 可視性設定 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">結果の公開範囲</label>
          <!-- i18n: surveys.create.resultsVisibility.label -->
          <Select
            v-model="resultsVisibility"
            :options="resultsVisibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">未回答者の可視範囲</label>
          <!-- i18n: surveys.create.unrespondedVisibility.label -->
          <Select
            v-model="unrespondedVisibility"
            :options="unrespondedVisibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>

      <!-- 締切 -->
      <div>
        <label class="mb-1 block text-sm font-medium">締切日時（任意）</label>
        <!-- i18n: surveys.create.deadline -->
        <DatePicker
          v-model="deadline"
          class="w-full"
          show-time
          show-icon
          hour-format="24"
          date-format="yy/mm/dd"
          placeholder="未設定"
        />
      </div>

      <!-- 設問エディタ -->
      <div>
        <label class="mb-2 block text-sm font-medium">
          設問 <span class="text-red-500">*</span>
          <!-- i18n: surveys.create.questions -->
        </label>
        <SurveyQuestionEditor v-model="questions" />
      </div>
    </div>

    <template #footer>
      <Button
        label="キャンセル"
        text
        severity="secondary"
        :disabled="submitting"
        @click="close"
      />
      <!-- i18n: surveys.create.cancel -->
      <Button
        label="保存"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
      <!-- i18n: surveys.create.save -->
    </template>
  </Dialog>
</template>
