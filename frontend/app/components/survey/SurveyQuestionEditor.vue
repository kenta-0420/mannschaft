<script setup lang="ts">
// F05.4 アンケート設問エディタ
// - 設問カードのリストを sortOrder 順で表示
// - 各カードで上下並び替え・削除・タイプ切替・選択肢編集を提供
// - 直接 push/splice せず、必ず新しい配列を組み立てて update:modelValue で emit する
// i18n: surveys.create.questionEditor.* （第三陣Dで置換予定）

import type { QuestionType } from '~/types/survey'

export interface QuestionDraft {
  questionText: string
  questionType: QuestionType
  isRequired: boolean
  sortOrder: number
  options?: Array<{ optionText: string; sortOrder: number }>
}

const props = defineProps<{
  modelValue: QuestionDraft[]
}>()

const emit = defineEmits<{
  'update:modelValue': [QuestionDraft[]]
}>()

// 設問タイプの選択肢
const questionTypeOptions: Array<{ label: string; value: QuestionType }> = [
  { label: '単一選択', value: 'SINGLE_CHOICE' }, // i18n: surveys.create.questionType.single
  { label: '複数選択', value: 'MULTIPLE_CHOICE' }, // i18n: surveys.create.questionType.multiple
  { label: '自由記述', value: 'TEXT' }, // i18n: surveys.create.questionType.text
  { label: '評価（1〜5）', value: 'RATING' }, // i18n: surveys.create.questionType.rating
  { label: '日付', value: 'DATE' }, // i18n: surveys.create.questionType.date
]

// modelValue を sortOrder 昇順で並べたビュー
const sortedQuestions = computed(() => {
  return [...props.modelValue].sort((a, b) => a.sortOrder - b.sortOrder)
})

// sortOrder を 1 始まりで振り直した新しい配列を返す
function reindex(list: QuestionDraft[]): QuestionDraft[] {
  return list.map((q, idx) => ({ ...q, sortOrder: idx + 1 }))
}

// 選択肢配列の sortOrder を 1 始まりに振り直す
function reindexOptions(
  options: Array<{ optionText: string; sortOrder: number }>,
): Array<{ optionText: string; sortOrder: number }> {
  return options.map((o, idx) => ({ ...o, sortOrder: idx + 1 }))
}

function emitNew(list: QuestionDraft[]) {
  emit('update:modelValue', reindex(list))
}

// === 操作: 設問追加 ===
function addQuestion() {
  const newQuestion: QuestionDraft = {
    questionText: '',
    questionType: 'SINGLE_CHOICE',
    isRequired: false,
    sortOrder: sortedQuestions.value.length + 1,
    options: [
      { optionText: '', sortOrder: 1 },
      { optionText: '', sortOrder: 2 },
    ],
  }
  emitNew([...sortedQuestions.value, newQuestion])
}

// === 操作: 設問削除 ===
function removeQuestion(index: number) {
  const next = sortedQuestions.value.filter((_, i) => i !== index)
  emitNew(next)
}

// === 操作: 上下移動 ===
function moveUp(index: number) {
  if (index <= 0) return
  const next = [...sortedQuestions.value]
  const prev = next[index - 1]
  const cur = next[index]
  if (!prev || !cur) return
  next[index - 1] = cur
  next[index] = prev
  emitNew(next)
}

function moveDown(index: number) {
  const list = sortedQuestions.value
  if (index >= list.length - 1) return
  const next = [...list]
  const cur = next[index]
  const nxt = next[index + 1]
  if (!cur || !nxt) return
  next[index] = nxt
  next[index + 1] = cur
  emitNew(next)
}

// === 操作: 設問プロパティ更新 ===
function updateQuestionText(index: number, value: string) {
  const next = sortedQuestions.value.map((q, i) =>
    i === index ? { ...q, questionText: value } : q,
  )
  emitNew(next)
}

function updateIsRequired(index: number, value: boolean) {
  const next = sortedQuestions.value.map((q, i) =>
    i === index ? { ...q, isRequired: value } : q,
  )
  emitNew(next)
}

// タイプ切替時に options を適切に再構築する
function updateQuestionType(index: number, type: QuestionType) {
  const next = sortedQuestions.value.map((q, i) => {
    if (i !== index) return q
    let options: QuestionDraft['options']
    if (type === 'SINGLE_CHOICE' || type === 'MULTIPLE_CHOICE') {
      // 既に選択肢があればそのまま、無ければ空欄2つで初期化
      const cur = q.options ?? []
      options = cur.length >= 2 ? cur : [
        { optionText: '', sortOrder: 1 },
        { optionText: '', sortOrder: 2 },
      ]
    } else if (type === 'RATING') {
      // 1〜5 固定生成
      options = [1, 2, 3, 4, 5].map((n) => ({ optionText: String(n), sortOrder: n }))
    } else {
      // TEXT / DATE は options を空に
      options = []
    }
    return { ...q, questionType: type, options }
  })
  emitNew(next)
}

// === 操作: 選択肢編集 ===
function updateOptionText(qIndex: number, oIndex: number, value: string) {
  const next = sortedQuestions.value.map((q, i) => {
    if (i !== qIndex) return q
    const opts = (q.options ?? []).map((o, oi) =>
      oi === oIndex ? { ...o, optionText: value } : o,
    )
    return { ...q, options: opts }
  })
  emitNew(next)
}

function addOption(qIndex: number) {
  const next = sortedQuestions.value.map((q, i) => {
    if (i !== qIndex) return q
    const opts = q.options ?? []
    const added = [...opts, { optionText: '', sortOrder: opts.length + 1 }]
    return { ...q, options: reindexOptions(added) }
  })
  emitNew(next)
}

function removeOption(qIndex: number, oIndex: number) {
  const next = sortedQuestions.value.map((q, i) => {
    if (i !== qIndex) return q
    const opts = (q.options ?? []).filter((_, oi) => oi !== oIndex)
    return { ...q, options: reindexOptions(opts) }
  })
  emitNew(next)
}

// 選択式判定
function isChoiceType(type: QuestionType): boolean {
  return type === 'SINGLE_CHOICE' || type === 'MULTIPLE_CHOICE'
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <div
      v-if="sortedQuestions.length === 0"
      class="rounded-lg border border-dashed border-surface-300 py-8 text-center text-sm text-surface-400"
    >
      <i class="pi pi-list mb-2 block text-2xl" />
      <p>設問がありません</p>
      <!-- i18n: surveys.create.questionEditor.empty -->
    </div>

    <div
      v-for="(question, qIndex) in sortedQuestions"
      :key="qIndex"
      class="rounded-xl border border-surface-300 bg-surface-0 p-4"
    >
      <!-- ヘッダー: 番号 + 並び替え + 削除 -->
      <div class="mb-3 flex items-center justify-between">
        <span class="text-sm font-semibold text-surface-700">
          設問 {{ qIndex + 1 }}
          <!-- i18n: surveys.create.questionEditor.questionNo -->
        </span>
        <div class="flex items-center gap-1">
          <Button
            icon="pi pi-arrow-up"
            text
            rounded
            severity="secondary"
            size="small"
            :disabled="qIndex === 0"
            aria-label="上へ移動"
            @click="moveUp(qIndex)"
          />
          <Button
            icon="pi pi-arrow-down"
            text
            rounded
            severity="secondary"
            size="small"
            :disabled="qIndex === sortedQuestions.length - 1"
            aria-label="下へ移動"
            @click="moveDown(qIndex)"
          />
          <Button
            icon="pi pi-trash"
            text
            rounded
            severity="danger"
            size="small"
            aria-label="削除"
            @click="removeQuestion(qIndex)"
          />
        </div>
      </div>

      <!-- 設問テキスト -->
      <div class="mb-3">
        <label class="mb-1 block text-sm font-medium">
          設問文 <span class="text-red-500">*</span>
          <!-- i18n: surveys.create.questionEditor.questionText -->
        </label>
        <InputText
          :model-value="question.questionText"
          class="w-full"
          maxlength="200"
          placeholder="質問内容を入力してください"
          @update:model-value="(v) => updateQuestionText(qIndex, v ?? '')"
        />
      </div>

      <!-- タイプ + 必須 -->
      <div class="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">回答形式</label>
          <!-- i18n: surveys.create.questionEditor.type -->
          <Select
            :model-value="question.questionType"
            :options="questionTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            @update:model-value="(v: QuestionType) => updateQuestionType(qIndex, v)"
          />
        </div>
        <div class="flex items-end">
          <label class="flex items-center gap-2 text-sm">
            <Checkbox
              :model-value="question.isRequired"
              binary
              @update:model-value="(v: boolean) => updateIsRequired(qIndex, v)"
            />
            <span>必須回答</span>
            <!-- i18n: surveys.create.questionEditor.required -->
          </label>
        </div>
      </div>

      <!-- 選択肢編集（SINGLE_CHOICE / MULTIPLE_CHOICE） -->
      <div v-if="isChoiceType(question.questionType)" class="space-y-2">
        <label class="block text-sm font-medium">選択肢</label>
        <!-- i18n: surveys.create.questionEditor.options -->
        <div
          v-for="(option, oIndex) in question.options ?? []"
          :key="oIndex"
          class="flex items-center gap-2"
        >
          <span class="w-6 text-center text-xs text-surface-400">{{ oIndex + 1 }}.</span>
          <InputText
            :model-value="option.optionText"
            class="flex-1"
            maxlength="200"
            placeholder="選択肢のテキスト"
            @update:model-value="(v) => updateOptionText(qIndex, oIndex, v ?? '')"
          />
          <Button
            icon="pi pi-times"
            text
            rounded
            severity="danger"
            size="small"
            :disabled="(question.options?.length ?? 0) <= 2"
            aria-label="選択肢を削除"
            @click="removeOption(qIndex, oIndex)"
          />
        </div>
        <Button
          label="選択肢を追加"
          icon="pi pi-plus"
          text
          severity="secondary"
          size="small"
          @click="addOption(qIndex)"
        />
        <!-- i18n: surveys.create.questionEditor.addOption -->
      </div>

      <!-- 評価スケール（RATING） -->
      <div
        v-else-if="question.questionType === 'RATING'"
        class="rounded-lg bg-surface-50 px-3 py-2 text-sm text-surface-500"
      >
        評価スケール（1〜5）
        <!-- i18n: surveys.create.questionEditor.ratingScale -->
      </div>

      <!-- TEXT / DATE は補足のみ -->
      <div
        v-else-if="question.questionType === 'TEXT'"
        class="rounded-lg bg-surface-50 px-3 py-2 text-sm text-surface-500"
      >
        回答者は自由記述で回答します
        <!-- i18n: surveys.create.questionEditor.textHint -->
      </div>
      <div
        v-else-if="question.questionType === 'DATE'"
        class="rounded-lg bg-surface-50 px-3 py-2 text-sm text-surface-500"
      >
        回答者は日付を選択して回答します
        <!-- i18n: surveys.create.questionEditor.dateHint -->
      </div>
    </div>

    <!-- 設問追加 -->
    <Button
      label="設問を追加"
      icon="pi pi-plus"
      severity="secondary"
      outlined
      class="self-start"
      @click="addQuestion"
    />
    <!-- i18n: surveys.create.questionEditor.addQuestion -->
  </div>
</template>
