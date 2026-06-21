<script setup lang="ts">
/**
 * F06.5 想起テスト画面（§3・AC-5〜8,22・§7 #10）。
 *
 * フロー:
 * 1. エントリ取得。isMasked=false なら「マスクされていない」案内＋エントリ詳細へ。
 * 2. isMasked=true なら本文は隠し、maskedHint（テーマ名・対象日・想起予定日）を表示。
 * 3. 想起入力フォーム＋自己評価（REMEMBERED/PARTIAL/FORGOT）→ POST recall（保存＝開示）。
 * 4. 開示後は original（structuredContent）と自分の想起を並べて表示する。
 *
 * recalled_content は自由テキスト（free_note のみ詰める簡易構造）で保存する（§2.4 同形 or 自由テキスト許容）。
 */
import {
  type ReflectionEntryResponse,
  type CreateRecallAttemptRequest,
  type RecallSelfRating,
  RECALL_SELF_RATINGS,
  emptyStructuredContent,
} from '~/types/reflection'
import { useReflectionStructuredContent, type JsonNode } from '~/composables/useReflectionStructuredContent'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const route = useRoute()
const { toStructured, toJsonNode } = useReflectionStructuredContent()

const entryId = computed(() => (route.query.entry as string) ?? '')

const loading = ref(true)
const entry = ref<ReflectionEntryResponse | null>(null)
const revealedEntry = ref<ReflectionEntryResponse | null>(null)
const submitting = ref(false)

// 想起入力（自由テキスト）と自己評価
const recallText = ref('')
const selfRating = ref<RecallSelfRating>('PARTIAL')

const ratingOptions = computed(() =>
  RECALL_SELF_RATINGS.map(r => ({ label: t(`reflection.recall.rating.${r}`), value: r })),
)

// 開示済み original の構造化表示
const revealedStructured = computed(() =>
  revealedEntry.value?.structuredContent
    ? toStructured(revealedEntry.value.structuredContent as unknown as JsonNode)
    : null,
)

onMounted(load)

async function load() {
  if (!entryId.value) {
    notification.error(t('reflection.common.load_failed'))
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await reflectionApi.getEntry(entryId.value)
    entry.value = res.data
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

async function submit() {
  submitting.value = true
  try {
    // recalled_content は free_note に想起テキストを詰めた構造（§2.4 自由テキスト許容）。
    const recalledContent = emptyStructuredContent()
    recalledContent.free_note = recallText.value
    const body: CreateRecallAttemptRequest = {
      recalledContent: toJsonNode(recalledContent) as CreateRecallAttemptRequest['recalledContent'],
      selfRating: selfRating.value,
    }
    const res = await reflectionApi.recordRecall(entryId.value, body)
    revealedEntry.value = res.data // 保存＝開示（original 返却・AC-7）
    notification.success(t('reflection.recall.saved'))
  }
  catch {
    notification.error(t('reflection.recall.save_failed'))
  }
  finally {
    submitting.value = false
  }
}

function goEntry() {
  router.push(`/reflections/entries/${entryId.value}`)
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-5 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded :aria-label="t('reflection.recall.back_to_entry')" @click="router.back()" />
      <h1 class="flex-1 text-xl font-bold">
        <i class="pi pi-bolt mr-2 text-primary" />{{ t('reflection.recall.heading') }}
      </h1>
    </div>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="120px" />
    </div>

    <template v-else-if="entry">
      <!-- 既に開示された（このセッションで recall 済み） -->
      <template v-if="revealedEntry">
        <div class="mb-4 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
          <h2 class="mb-2 text-sm font-semibold text-surface-600 dark:text-surface-300">{{ t('reflection.recall.your_recall') }}</h2>
          <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-200">{{ recallText || '—' }}</p>
        </div>
        <div class="rounded-xl border border-green-200 bg-green-50 p-4 dark:border-green-700/50 dark:bg-green-900/20">
          <h2 class="mb-2 text-sm font-semibold text-green-700 dark:text-green-300">{{ t('reflection.recall.revealed_heading') }}</h2>
          <ReflectionStructuredView v-if="revealedStructured" :content="revealedStructured" />
        </div>
        <div class="mt-4 flex justify-end">
          <Button :label="t('reflection.recall.back_to_entry')" icon="pi pi-arrow-right" icon-pos="right" @click="goEntry" />
        </div>
      </template>

      <!-- マスクされていない（当日 or 開示済み）：通常編集へ誘導 -->
      <div v-else-if="!entry.isMasked" class="rounded-xl border border-surface-200 bg-surface-0 p-6 text-center dark:border-surface-700 dark:bg-surface-800">
        <p class="mb-4 text-sm text-surface-500">{{ t('reflection.recall.not_masked') }}</p>
        <Button :label="t('reflection.recall.back_to_entry')" icon="pi pi-pencil" @click="goEntry" />
      </div>

      <!-- 想起テスト本番（マスク中） -->
      <template v-else>
        <!-- ヒント（本文は出さない） -->
        <div class="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4 dark:border-amber-700/50 dark:bg-amber-900/20">
          <p class="mb-2 text-sm text-surface-600 dark:text-surface-300">{{ t('reflection.recall.intro') }}</p>
          <dl class="space-y-1 text-sm">
            <div class="flex gap-2">
              <dt class="w-28 text-surface-500">{{ t('reflection.recall.hint_theme') }}</dt>
              <dd class="font-medium">{{ entry.maskedHint?.themeTitle }}</dd>
            </div>
            <div class="flex gap-2">
              <dt class="w-28 text-surface-500">{{ t('reflection.recall.hint_target_date') }}</dt>
              <dd>{{ entry.maskedHint?.targetDate ?? entry.targetDate }}</dd>
            </div>
            <div v-if="entry.maskedHint?.dueRecallDates?.length" class="flex gap-2">
              <dt class="w-28 text-surface-500">{{ t('reflection.recall.hint_due_dates') }}</dt>
              <dd>{{ entry.maskedHint.dueRecallDates.join(' / ') }}</dd>
            </div>
          </dl>
        </div>

        <!-- 想起入力 -->
        <div class="space-y-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('reflection.recall.input_label') }}</label>
            <Textarea
              v-model="recallText"
              :placeholder="t('reflection.recall.input_placeholder')"
              class="w-full"
              rows="6"
              auto-resize
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('reflection.recall.self_rating_label') }}</label>
            <div class="flex flex-wrap gap-2">
              <div
                v-for="opt in ratingOptions"
                :key="opt.value"
                class="flex cursor-pointer items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm transition-colors"
                :class="selfRating === opt.value
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-surface-300 text-surface-500 dark:border-surface-600'"
                @click="selfRating = opt.value"
              >
                <RadioButton v-model="selfRating" :value="opt.value" />
                <span>{{ opt.label }}</span>
              </div>
            </div>
          </div>
          <Button
            :label="t('reflection.recall.submit')"
            :loading="submitting"
            icon="pi pi-eye"
            class="w-full"
            @click="submit"
          />
        </div>
      </template>
    </template>
  </div>
</template>
