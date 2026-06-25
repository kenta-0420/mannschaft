<script setup lang="ts">
/**
 * F06.5 Phase 4 期間横断 単語帳ビュー（EP #23・§13-F/§13-G）。
 *
 * 指定期間の TERM_CARD カードを横断抽出し、一覧表示または自己クイズで使える。
 * recall_attempts への書込は一切行わない（スケジュール想起と独立）。
 *
 * Phase 4.1 追加（AC-62〜68）:
 * - 教科フィルタ（マルチセレクト・subjects[]）
 * - シャッフルトグル（毎回順番が変わる）
 * - 500件超警告（シャッフル時）
 */
import type { ReflectionThemeResponse, ReflectionVocabCardItem, VocabQuizDirection } from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()

// 期間フィルタ（初期: 過去30日）
const today = new Date()
const thirtyDaysAgo = new Date(today)
thirtyDaysAgo.setDate(today.getDate() - 30)

const fromDate = ref(thirtyDaysAgo.toISOString().slice(0, 10))
const toDate = ref(today.toISOString().slice(0, 10))

const loading = ref(false)
const cards = ref<ReflectionVocabCardItem[]>([])
const totalCards = ref(0)

// 教科フィルタ（AC-62）
const allSubjects = ref<string[]>([])
const selectedSubjects = ref<string[]>([])

// シャッフルトグル（AC-63）
const shuffleEnabled = ref(false)

// クイズモード
const mode = ref<'view' | 'quiz'>('view')
const direction = ref<VocabQuizDirection>('TERM_TO_MEANING')
const currentIndex = ref(0)
const flipped = ref(false)

const quizCards = computed(() => {
  if (direction.value === 'SHUFFLE') {
    return [...cards.value].sort(() => Math.random() - 0.5)
  }
  return cards.value
})

const currentCard = computed(() => quizCards.value[currentIndex.value] ?? null)

const cueText = computed(() => {
  if (!currentCard.value) return ''
  return direction.value === 'MEANING_TO_TERM'
    ? currentCard.value.meaning
    : currentCard.value.term
})

const answerText = computed(() => {
  if (!currentCard.value) return ''
  return direction.value === 'MEANING_TO_TERM'
    ? currentCard.value.term
    : currentCard.value.meaning
})

// 500件超警告（シャッフル時・AC-63）
const showCardLimitWarning = computed(() => shuffleEnabled.value && totalCards.value > 500)

/** テーマ一覧から教科名を収集 */
async function loadSubjects() {
  try {
    const res = await reflectionApi.listThemes()
    const themes: ReflectionThemeResponse[] = res.data ?? []
    const subjects = new Set<string>()
    for (const theme of themes) {
      if (theme.linkedSubjectName) {
        subjects.add(theme.linkedSubjectName)
      }
    }
    allSubjects.value = Array.from(subjects).sort()
  }
  catch {
    // 教科一覧取得に失敗しても検索は続行できる
  }
}

function toggleSubject(subject: string) {
  const idx = selectedSubjects.value.indexOf(subject)
  if (idx === -1) {
    selectedSubjects.value = [...selectedSubjects.value, subject]
  }
  else {
    selectedSubjects.value = selectedSubjects.value.filter(s => s !== subject)
  }
}

function selectAllSubjects() {
  selectedSubjects.value = []
}

async function search() {
  if (!fromDate.value || !toDate.value) return
  loading.value = true
  currentIndex.value = 0
  flipped.value = false
  try {
    const res = await reflectionApi.getVocabCards({
      from: fromDate.value,
      to: toDate.value,
      subjects: selectedSubjects.value.length > 0 ? selectedSubjects.value : undefined,
      shuffle: shuffleEnabled.value || undefined,
    })
    cards.value = res.data.cards ?? []
    totalCards.value = res.data.totalCards ?? 0
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function nextCard() {
  if (currentIndex.value < quizCards.value.length - 1) {
    currentIndex.value++
    flipped.value = false
  }
}

function prevCard() {
  if (currentIndex.value > 0) {
    currentIndex.value--
    flipped.value = false
  }
}

onMounted(async () => {
  await loadSubjects()
  await search()
})
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 py-6">
    <PageHeader
      :title="t('reflection.vocab.title')"
      :back-label="t('button.back')"
      back-to="/reflections"
    />

    <!-- 期間・フィルタパネル -->
    <div class="mb-4 space-y-3 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
      <!-- 期間フィルタ行 -->
      <div class="flex flex-wrap items-center gap-3">
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600">{{ t('reflection.vocab.period_from') }}</label>
          <InputText v-model="fromDate" type="date" size="small" />
        </div>
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600">{{ t('reflection.vocab.period_to') }}</label>
          <InputText v-model="toDate" type="date" size="small" />
        </div>
        <Button :label="t('button.search')" icon="pi pi-search" size="small" @click="search" />
      </div>

      <!-- 教科フィルタ（AC-62）: 教科が1件以上あるときのみ表示 -->
      <div v-if="allSubjects.length > 0">
        <p class="mb-1 text-xs text-surface-500">{{ t('reflection.vocab.subjects_filter') }}</p>
        <div class="flex flex-wrap gap-2">
          <!-- 「全教科」チップ -->
          <button
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="selectedSubjects.length === 0
              ? 'border-primary bg-primary text-white'
              : 'border-surface-300 bg-surface-0 text-surface-600 hover:border-primary dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300'"
            @click="selectAllSubjects"
          >
            {{ t('reflection.vocab.subjects_all') }}
          </button>
          <!-- 教科チップ -->
          <button
            v-for="subject in allSubjects"
            :key="subject"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="selectedSubjects.includes(subject)
              ? 'border-primary bg-primary text-white'
              : 'border-surface-300 bg-surface-0 text-surface-600 hover:border-primary dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300'"
            @click="toggleSubject(subject)"
          >
            {{ subject }}
          </button>
        </div>
      </div>

      <!-- シャッフルトグル（AC-63）-->
      <div class="flex items-center gap-3">
        <ToggleSwitch v-model="shuffleEnabled" input-id="shuffle-toggle" />
        <label for="shuffle-toggle" class="cursor-pointer text-sm text-surface-600 dark:text-surface-300">
          {{ t('reflection.vocab.shuffle') }}
        </label>
        <span class="text-xs text-surface-400">{{ t('reflection.vocab.shuffle_note') }}</span>
      </div>

      <!-- 500件超警告（AC-63）-->
      <Message v-if="showCardLimitWarning" severity="warn" :closable="false">
        {{ t('reflection.vocab.card_limit_warning') }}
      </Message>
    </div>

    <!-- モード切替 -->
    <div class="mb-4 flex items-center gap-2">
      <Button
        :label="t('reflection.vocab.mode.view')"
        size="small"
        :outlined="mode !== 'view'"
        @click="mode = 'view'"
      />
      <Button
        :label="t('reflection.vocab.mode.quiz')"
        size="small"
        :outlined="mode !== 'quiz'"
        :disabled="cards.length === 0"
        @click="mode = 'quiz'; currentIndex = 0; flipped = false"
      />
      <span v-if="totalCards > 0" class="ml-auto text-sm text-surface-500">
        {{ t('reflection.vocab.total_cards', { n: totalCards }) }}
      </span>
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-3">
      <Skeleton height="80px" />
      <Skeleton height="80px" />
    </div>

    <!-- 空状態 -->
    <div
      v-else-if="!loading && cards.length === 0"
      class="rounded-xl border border-surface-200 bg-surface-0 p-8 text-center dark:border-surface-700 dark:bg-surface-800"
    >
      <p class="text-surface-500">{{ t('reflection.vocab.empty') }}</p>
    </div>

    <!-- 一覧モード -->
    <template v-else-if="mode === 'view'">
      <div class="space-y-2">
        <div
          v-for="(card, i) in cards"
          :key="i"
          class="flex items-start gap-4 rounded-xl border border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-800"
        >
          <span class="w-1/2 text-sm font-medium">{{ card.term }}</span>
          <span class="text-surface-400">→</span>
          <span class="w-1/2 text-sm text-surface-600 dark:text-surface-300">{{ card.meaning }}</span>
        </div>
      </div>
    </template>

    <!-- クイズモード（フリップカード）-->
    <template v-else>
      <!-- 方向トグル -->
      <div class="mb-4 flex gap-2">
        <Button
          v-for="dir in (['TERM_TO_MEANING', 'MEANING_TO_TERM', 'SHUFFLE'] as VocabQuizDirection[])"
          :key="dir"
          :label="t(`reflection.vocab.direction.${dir.toLowerCase()}`)"
          size="small"
          :outlined="direction !== dir"
          @click="direction = dir; currentIndex = 0; flipped = false"
        />
      </div>

      <!-- フリップカード -->
      <div
        v-if="currentCard"
        class="mb-4 cursor-pointer rounded-xl border border-primary/30 bg-primary/5 p-8 text-center transition-all"
        @click="flipped = !flipped"
      >
        <p class="mb-2 text-xs text-surface-400">{{ flipped ? t('reflection.vocab.direction.term_to_meaning') : t('reflection.vocab.direction.meaning_to_term') }}</p>
        <p class="text-lg font-bold">{{ flipped ? answerText : cueText }}</p>
        <p class="mt-4 text-xs text-surface-400">{{ t('reflection.vocab.flip') }}</p>
      </div>

      <!-- ナビゲーション -->
      <div class="flex items-center justify-between">
        <Button icon="pi pi-chevron-left" :disabled="currentIndex === 0" @click="prevCard" />
        <span class="text-sm text-surface-500">{{ currentIndex + 1 }} / {{ quizCards.length }}</span>
        <Button icon="pi pi-chevron-right" :disabled="currentIndex >= quizCards.length - 1" @click="nextCard" />
      </div>
    </template>
  </div>
</template>
