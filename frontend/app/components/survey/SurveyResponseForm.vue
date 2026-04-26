<script setup lang="ts">
import type {
  SurveyDetailResponse,
  SurveyQuestion,
  SubmitResponseRequest,
} from '~/types/survey'

// i18n: surveys.detail.form.*
const props = defineProps<{
  survey: SurveyDetailResponse['data']
  alreadyResponded: boolean
  allowMultiple: boolean
}>()

const emit = defineEmits<{
  submitted: []
}>()

const { submitResponse, getMyResponse } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()

/**
 * 各設問の回答状態。
 * 設問タイプに応じて格納される値の型は変わる:
 *  - SINGLE_CHOICE: number（optionId）
 *  - MULTIPLE_CHOICE: number[]（optionIds）
 *  - TEXT: string
 *  - RATING: number（1〜5）
 *  - DATE: Date | null（送信時に ISO 文字列へ変換）
 */
type AnswerValue = number | number[] | string | Date | null
const answers = ref<Record<number, AnswerValue>>({})
const submitting = ref(false)
const validationError = ref<string | null>(null)

/** 設問は sortOrder 昇順で並べる */
const sortedQuestions = computed<SurveyQuestion[]>(() =>
  [...props.survey.questions].sort((a, b) => a.sortOrder - b.sortOrder),
)

/** RATING 用の選択肢を 1〜5 で正規化（options が無い場合のフォールバック） */
function ratingOptions(question: SurveyQuestion): Array<{ id: number; label: string }> {
  if (question.options && question.options.length > 0) {
    return question.options
      .slice()
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((o) => ({ id: o.id, label: o.optionText }))
  }
  // フォールバック: 1〜5 の数値ラベル（id は便宜上 1〜5 を使うが送信は ratingValue）
  return [1, 2, 3, 4, 5].map((n) => ({ id: n, label: String(n) }))
}

/** 既回答取得（allow_multiple_submissions = true 時の prefill） */
async function prefillFromMyResponse() {
  try {
    const res = await getMyResponse(props.survey.id)
    const data = (res as { data?: { answers?: SubmitResponseRequest['answers'] } } | undefined)
      ?.data
    const past = data?.answers
    if (!Array.isArray(past)) return
    for (const ans of past) {
      const q = sortedQuestions.value.find((x) => x.id === ans.questionId)
      if (!q) continue
      switch (q.questionType) {
        case 'SINGLE_CHOICE':
          if (typeof ans.optionId === 'number') answers.value[q.id] = ans.optionId
          break
        case 'MULTIPLE_CHOICE':
          if (Array.isArray(ans.optionIds)) answers.value[q.id] = [...ans.optionIds]
          break
        case 'TEXT':
          if (typeof ans.textValue === 'string') answers.value[q.id] = ans.textValue
          break
        case 'RATING':
          if (typeof ans.ratingValue === 'number') answers.value[q.id] = ans.ratingValue
          break
        case 'DATE':
          if (typeof ans.dateValue === 'string' && ans.dateValue) {
            const d = new Date(ans.dateValue)
            if (!Number.isNaN(d.getTime())) answers.value[q.id] = d
          }
          break
      }
    }
  } catch {
    // 取得失敗は無視（未回答扱い）
  }
}

/** 必須バリデーション */
function validate(): string | null {
  for (const q of sortedQuestions.value) {
    if (!q.isRequired) continue
    const v = answers.value[q.id]
    switch (q.questionType) {
      case 'SINGLE_CHOICE':
      case 'RATING':
        if (typeof v !== 'number') return `「${q.questionText}」は必須項目です`
        break
      case 'MULTIPLE_CHOICE':
        if (!Array.isArray(v) || v.length === 0) {
          return `「${q.questionText}」は必須項目です`
        }
        break
      case 'TEXT':
        if (typeof v !== 'string' || v.trim().length === 0) {
          return `「${q.questionText}」は必須項目です`
        }
        break
      case 'DATE':
        if (!(v instanceof Date) || Number.isNaN(v.getTime())) {
          return `「${q.questionText}」は必須項目です`
        }
        break
    }
  }
  // TEXT 上限チェック（必須かどうかに関わらず）
  for (const q of sortedQuestions.value) {
    if (q.questionType !== 'TEXT') continue
    const v = answers.value[q.id]
    if (typeof v === 'string' && v.length > 2000) {
      return `「${q.questionText}」は2000文字以内で入力してください`
    }
  }
  return null
}

function buildPayload(): SubmitResponseRequest {
  const out: SubmitResponseRequest = { answers: [] }
  for (const q of sortedQuestions.value) {
    const v = answers.value[q.id]
    switch (q.questionType) {
      case 'SINGLE_CHOICE':
        if (typeof v === 'number') {
          out.answers.push({ questionId: q.id, optionId: v })
        }
        break
      case 'MULTIPLE_CHOICE':
        if (Array.isArray(v) && v.length > 0) {
          out.answers.push({ questionId: q.id, optionIds: [...v] })
        }
        break
      case 'TEXT':
        if (typeof v === 'string' && v.length > 0) {
          out.answers.push({ questionId: q.id, textValue: v })
        }
        break
      case 'RATING':
        if (typeof v === 'number') {
          out.answers.push({ questionId: q.id, ratingValue: v })
        }
        break
      case 'DATE':
        if (v instanceof Date && !Number.isNaN(v.getTime())) {
          out.answers.push({ questionId: q.id, dateValue: v.toISOString() })
        }
        break
    }
  }
  return out
}

async function onSubmit() {
  validationError.value = null
  const err = validate()
  if (err) {
    validationError.value = err
    showError(err)
    return
  }
  submitting.value = true
  try {
    await submitResponse(props.survey.id, buildPayload() as unknown as Record<string, unknown>)
    showSuccess('回答を送信しました')
    emit('submitted')
  } catch {
    showError('回答の送信に失敗しました')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (props.alreadyResponded && props.allowMultiple) {
    prefillFromMyResponse()
  }
})
</script>

<template>
  <!-- i18n: surveys.detail.form.alreadyResponded -->
  <div
    v-if="alreadyResponded && !allowMultiple"
    class="rounded-lg border border-green-200 bg-green-50 p-4 text-sm text-green-700 dark:border-green-700 dark:bg-green-900/20 dark:text-green-200"
  >
    <i class="pi pi-check-circle mr-2" />
    このアンケートには既に回答済みです。
  </div>

  <form v-else class="flex flex-col gap-6" @submit.prevent="onSubmit">
    <div
      v-for="q in sortedQuestions"
      :key="q.id"
      class="rounded-lg border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
    >
      <p class="mb-3 text-sm font-semibold text-surface-800 dark:text-surface-100">
        {{ q.questionText }}
        <span
          v-if="q.isRequired"
          class="ml-1 rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-600"
          >必須</span
        >
      </p>

      <!-- SINGLE_CHOICE -->
      <div v-if="q.questionType === 'SINGLE_CHOICE'" class="flex flex-col gap-2">
        <label
          v-for="opt in q.options"
          :key="opt.id"
          class="flex cursor-pointer items-center gap-2 text-sm"
        >
          <RadioButton
            v-model="answers[q.id]"
            :name="`q-${q.id}`"
            :value="opt.id"
          />
          <span>{{ opt.optionText }}</span>
        </label>
      </div>

      <!-- MULTIPLE_CHOICE -->
      <div v-else-if="q.questionType === 'MULTIPLE_CHOICE'" class="flex flex-col gap-2">
        <label
          v-for="opt in q.options"
          :key="opt.id"
          class="flex cursor-pointer items-center gap-2 text-sm"
        >
          <Checkbox
            v-model="answers[q.id]"
            :name="`q-${q.id}`"
            :value="opt.id"
          />
          <span>{{ opt.optionText }}</span>
        </label>
      </div>

      <!-- TEXT -->
      <div v-else-if="q.questionType === 'TEXT'">
        <Textarea
          v-model="answers[q.id] as string"
          rows="4"
          maxlength="2000"
          class="w-full"
          placeholder="回答を入力してください"
        />
        <p class="mt-1 text-right text-xs text-surface-400">
          {{ (typeof answers[q.id] === 'string' ? (answers[q.id] as string).length : 0) }} / 2000
        </p>
      </div>

      <!-- RATING -->
      <div v-else-if="q.questionType === 'RATING'" class="flex flex-wrap items-center gap-3">
        <label
          v-for="r in ratingOptions(q)"
          :key="r.id"
          class="flex cursor-pointer items-center gap-1.5 text-sm"
        >
          <RadioButton
            v-model="answers[q.id]"
            :name="`q-${q.id}`"
            :value="r.id"
          />
          <span>{{ r.label }}</span>
        </label>
      </div>

      <!-- DATE -->
      <div v-else-if="q.questionType === 'DATE'">
        <DatePicker
          v-model="answers[q.id] as Date | null"
          date-format="yy/mm/dd"
          show-icon
          class="w-full sm:w-64"
        />
      </div>
    </div>

    <div
      v-if="validationError"
      class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-900/20 dark:text-red-200"
    >
      <i class="pi pi-exclamation-circle mr-1" />
      {{ validationError }}
    </div>

    <div class="flex justify-end">
      <Button
        type="submit"
        :loading="submitting"
        :label="alreadyResponded && allowMultiple ? '回答を更新' : '回答を送信'"
        icon="pi pi-send"
      />
    </div>
  </form>
</template>
