<script setup lang="ts">
import type {
  CustomFaq,
  FaqEditorResponse,
  SaveFaqRequest,
} from '~/types/faq'

/**
 * F21.1 §5.5: FAQ 設定エディタ（チーム / 組織 共通）。
 *
 * カテゴリ別の固定 6 問（回答 textarea）と、自由質問（最大 7 件・add/remove/並び替え）を
 * 編集し、一括保存で `SaveFaqRequest` を構築して PUT する。
 * 認可は `useRoleAccess` で判定し、非管理者にはアクセス不可表示を出す。
 *
 * 設計書: docs/features/F21.1_geo_optimization.md §5.5.6
 */

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const MAX_CUSTOM_FAQS = 7
const MAX_ANSWER_LENGTH = 1000
const MAX_QUESTION_TEXT_LENGTH = 255

const { t } = useI18n()
const toast = useToast()
const faqApi = useFaqApi()
const { isAdmin, loadPermissions } = useRoleAccess(props.scopeType, props.scopeId)

const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)

const category = ref<string>('')
const fixedAnswers = ref<{ questionKey: string, displayOrder: number, answer: string }[]>([])
const customFaqs = ref<CustomFaq[]>([])

/** 固定質問ラベルの i18n キー（faq.fixed.{key.toLowerCase()}）。 */
function fixedLabel(questionKey: string): string {
  return t(`faq.fixed.${questionKey.toLowerCase()}`)
}

/** カテゴリバッジのラベル（faq.category.{CATEGORY}）。 */
const categoryLabel = computed(() =>
  category.value ? t(`faq.category.${category.value}`) : '',
)

const canAddCustom = computed(() => customFaqs.value.length < MAX_CUSTOM_FAQS)

async function load() {
  loading.value = true
  try {
    const res: FaqEditorResponse = props.scopeType === 'team'
      ? await faqApi.fetchTeamFaqEditor(props.scopeId)
      : await faqApi.fetchOrgFaqEditor(props.scopeId)
    category.value = res.category
    // 固定質問は displayOrder 昇順・全件。answer=null は空文字に正規化して編集する。
    fixedAnswers.value = res.fixedQuestions.map(q => ({
      questionKey: q.questionKey,
      displayOrder: q.displayOrder,
      answer: q.answer ?? '',
    }))
    customFaqs.value = res.customFaqs.map(c => ({ ...c }))
  }
  finally {
    loading.value = false
    loaded.value = true
  }
}

function addCustom() {
  if (!canAddCustom.value) {
    toast.add({ severity: 'warn', summary: t('faq.settings.customLimit'), life: 3000 })
    return
  }
  customFaqs.value.push({
    id: null,
    questionText: '',
    answer: '',
    displayOrder: customFaqs.value.length,
  })
}

function removeCustom(index: number) {
  customFaqs.value.splice(index, 1)
  renumberCustom()
}

function moveCustom(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= customFaqs.value.length) return
  const list = customFaqs.value
  const current = list[index]
  const swap = list[target]
  if (!current || !swap) return
  list[index] = swap
  list[target] = current
  renumberCustom()
}

/** 並び替え / 削除後に displayOrder を 0 始まりで振り直す。 */
function renumberCustom() {
  customFaqs.value.forEach((c, i) => {
    c.displayOrder = i
  })
}

/** 保存前のクライアント側バリデーション。NG ならエラートーストを出して false を返す。 */
function validate(): boolean {
  for (const fa of fixedAnswers.value) {
    if (fa.answer.length > MAX_ANSWER_LENGTH) {
      toast.add({ severity: 'error', summary: t('faq.settings.answerTooLong'), life: 5000 })
      return false
    }
  }
  for (const c of customFaqs.value) {
    if (!c.questionText.trim()) {
      toast.add({ severity: 'error', summary: t('faq.settings.questionRequired'), life: 5000 })
      return false
    }
    if (c.answer.length > MAX_ANSWER_LENGTH) {
      toast.add({ severity: 'error', summary: t('faq.settings.answerTooLong'), life: 5000 })
      return false
    }
  }
  return true
}

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    // 固定質問は 6 問すべて送る（空欄は answer="" でクリア扱い）。
    const req: SaveFaqRequest = {
      fixedAnswers: fixedAnswers.value.map(fa => ({
        questionKey: fa.questionKey,
        answer: fa.answer,
      })),
      customFaqs: customFaqs.value.map((c, i) => ({
        id: c.id,
        questionText: c.questionText,
        answer: c.answer,
        displayOrder: i,
      })),
    }
    if (props.scopeType === 'team') {
      await faqApi.saveTeamFaqs(props.scopeId, req)
    }
    else {
      await faqApi.saveOrgFaqs(props.scopeId, req)
    }
    toast.add({ severity: 'success', summary: t('faq.settings.saved'), life: 3000 })
    // 新規追加分に採番された id を反映するため再読込する。
    await load()
  }
  catch {
    toast.add({ severity: 'error', summary: t('faq.settings.saveError'), life: 5000 })
  }
  finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadPermissions()
  if (isAdmin.value) {
    await load()
  }
  else {
    loaded.value = true
  }
})
</script>

<template>
  <div class="space-y-6">
    <div
      v-if="loaded && !isAdmin"
      class="rounded-lg border border-surface-200 p-6 text-center dark:border-surface-700"
      data-testid="faq-access-denied"
    >
      <i class="pi pi-lock mb-2 text-2xl text-surface-400" />
      <p class="text-surface-500">
        {{ t('faq.settings.accessDenied') }}
      </p>
    </div>

    <template v-else>
      <h1 class="text-2xl font-bold">
        {{ t('faq.settings.title') }}
      </h1>

      <div
        v-if="loading"
        class="flex items-center justify-center py-10"
      >
        <ProgressSpinner />
      </div>

      <form
        v-else
        :data-testid="`faq-settings-form-${props.scopeType}`"
        class="space-y-8"
        @submit.prevent="save"
      >
        <!-- カテゴリ（読み取り専用） -->
        <div class="flex items-center gap-3">
          <span class="text-sm font-medium">{{ t('faq.settings.categoryLabel') }}</span>
          <Tag
            :value="categoryLabel"
            severity="info"
            data-testid="faq-category-badge"
          />
        </div>
        <p class="-mt-4 text-xs text-surface-500">
          {{ t('faq.settings.categoryHint') }}
        </p>

        <!-- 固定質問 -->
        <section class="space-y-4">
          <h2 class="text-lg font-semibold">
            {{ t('faq.settings.fixedSection') }}
          </h2>
          <div
            v-for="fa in fixedAnswers"
            :key="fa.questionKey"
            class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
            :data-testid="`faq-fixed-${fa.questionKey}`"
          >
            <label
              class="mb-1 block text-sm font-medium"
              :for="`faq-fixed-input-${fa.questionKey}`"
            >
              {{ fixedLabel(fa.questionKey) }}
            </label>
            <Textarea
              :id="`faq-fixed-input-${fa.questionKey}`"
              v-model="fa.answer"
              class="w-full"
              rows="3"
              auto-resize
              :maxlength="MAX_ANSWER_LENGTH"
              :placeholder="t('faq.settings.answerPlaceholder')"
            />
            <p class="mt-1 text-right text-xs text-surface-400">
              {{ fa.answer.length }} / {{ MAX_ANSWER_LENGTH }}
            </p>
          </div>
        </section>

        <!-- 自由質問 -->
        <section class="space-y-4">
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-semibold">
              {{ t('faq.settings.customSection') }}
            </h2>
            <Button
              type="button"
              icon="pi pi-plus"
              :label="t('faq.settings.addCustom')"
              :disabled="!canAddCustom"
              data-testid="faq-add-custom"
              @click="addCustom"
            />
          </div>
          <p
            v-if="!canAddCustom"
            class="text-xs text-amber-600"
            data-testid="faq-custom-limit"
          >
            {{ t('faq.settings.customLimit') }}
          </p>

          <div
            v-for="(c, index) in customFaqs"
            :key="c.id ?? `new-${index}`"
            class="space-y-2 rounded-lg border border-surface-200 p-4 dark:border-surface-700"
            :data-testid="`faq-custom-${index}`"
          >
            <div class="flex items-start gap-2">
              <div class="flex-1 space-y-2">
                <InputText
                  v-model="c.questionText"
                  class="w-full"
                  :maxlength="MAX_QUESTION_TEXT_LENGTH"
                  :placeholder="t('faq.settings.questionPlaceholder')"
                />
                <Textarea
                  v-model="c.answer"
                  class="w-full"
                  rows="3"
                  auto-resize
                  :maxlength="MAX_ANSWER_LENGTH"
                  :placeholder="t('faq.settings.answerPlaceholder')"
                />
                <p class="text-right text-xs text-surface-400">
                  {{ c.answer.length }} / {{ MAX_ANSWER_LENGTH }}
                </p>
              </div>
              <div class="flex flex-col gap-1">
                <Button
                  type="button"
                  icon="pi pi-chevron-up"
                  text
                  rounded
                  :disabled="index === 0"
                  :aria-label="t('faq.settings.moveUp')"
                  :data-testid="`faq-custom-up-${index}`"
                  @click="moveCustom(index, -1)"
                />
                <Button
                  type="button"
                  icon="pi pi-chevron-down"
                  text
                  rounded
                  :disabled="index === customFaqs.length - 1"
                  :aria-label="t('faq.settings.moveDown')"
                  :data-testid="`faq-custom-down-${index}`"
                  @click="moveCustom(index, 1)"
                />
                <Button
                  type="button"
                  icon="pi pi-trash"
                  text
                  rounded
                  severity="danger"
                  :aria-label="t('faq.settings.removeCustom')"
                  :data-testid="`faq-custom-remove-${index}`"
                  @click="removeCustom(index)"
                />
              </div>
            </div>
          </div>
        </section>

        <div class="flex justify-end">
          <Button
            type="submit"
            :loading="saving"
            :label="t('faq.settings.save')"
            data-testid="faq-save"
          />
        </div>
      </form>
    </template>
  </div>
</template>
