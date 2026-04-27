<script setup lang="ts">
// F05.4 アンケート新規作成ダイアログ
// - PrimeVue Dialog で全項目を1ページに集約
// - 設問編集は SurveyQuestionEditor を v-model で組み込み
// - 簡易バリデーションを通過後 useSurveyApi().createSurvey を呼び出す

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

const { t } = useI18n()
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
const resultsVisibilityOptions = computed<Array<{ label: string; value: ResultsVisibility }>>(() => [
  { label: t('surveys.resultsVisibility.CREATOR_ONLY'), value: 'CREATOR_ONLY' },
  { label: t('surveys.resultsVisibility.RESPONDENTS'), value: 'RESPONDENTS' },
  { label: t('surveys.resultsVisibility.ALL_MEMBERS'), value: 'ALL_MEMBERS' },
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
  if (!title.value.trim()) {
    return t('surveys.create.validation.titleRequired')
  }
  if (title.value.trim().length > 200) {
    return t('surveys.create.validation.titleTooLong')
  }
  if (description.value.length > 1000) {
    return t('surveys.create.validation.descriptionTooLong')
  }
  if (questions.value.length === 0) {
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
    showSuccess(t('surveys.create.successToast'))
    emit('created', res.data)
    resetForm()
    close()
  } catch {
    showError(t('surveys.create.failureToast'))
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
    :header="t('surveys.create.dialogHeader')"
    @hide="resetForm"
  >
    <div class="flex flex-col gap-4">
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
        />
      </div>

      <!-- オプション群 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="flex items-center gap-2 text-sm">
          <Checkbox v-model="isAnonymous" binary />
          <span>{{ t('surveys.create.isAnonymous') }}</span>
        </label>
        <label class="flex items-center gap-2 text-sm">
          <Checkbox v-model="allowMultipleSubmissions" binary />
          <span>{{ t('surveys.create.allowMultipleSubmissions') }}</span>
        </label>
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
        />
      </div>

      <!-- 設問エディタ -->
      <div>
        <label class="mb-2 block text-sm font-medium">
          {{ t('surveys.create.questions') }} <span class="text-red-500">*</span>
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
        @click="close"
      />
      <Button
        :label="t('surveys.create.save')"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
