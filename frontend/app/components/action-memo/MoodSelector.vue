<script setup lang="ts">
import type { Mood } from '~/types/actionMemo'

/**
 * F02.5 mood セレクタ。
 *
 * <p>5段階の気分を絵文字付きボタンで選択する。{@code v-model} で {@code Mood | null} を返す。
 * 「選択しない」ボタンで NULL に戻すことができる。</p>
 *
 * <p>絵文字は言語非依存に直接埋め込み、ラベルのみ i18n キーで翻訳する。</p>
 */

const model = defineModel<Mood | null>({ required: true })

const { t } = useI18n()

interface MoodOption {
  value: Mood
  emoji: string
  i18nKey: string
  /** Tailwind バッジ色 */
  badgeClass: string
}

const options: MoodOption[] = [
  {
    value: 'GREAT',
    emoji: '😄',
    i18nKey: 'action_memo.mood.great',
    badgeClass: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
  },
  {
    value: 'GOOD',
    emoji: '🙂',
    i18nKey: 'action_memo.mood.good',
    badgeClass: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
  },
  {
    value: 'OK',
    emoji: '😐',
    i18nKey: 'action_memo.mood.ok',
    badgeClass: 'bg-surface-100 text-surface-700 dark:bg-surface-700 dark:text-surface-200',
  },
  {
    value: 'TIRED',
    emoji: '😩',
    i18nKey: 'action_memo.mood.tired',
    badgeClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300',
  },
  {
    value: 'BAD',
    emoji: '😞',
    i18nKey: 'action_memo.mood.bad',
    badgeClass: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300',
  },
]

function select(value: Mood) {
  model.value = model.value === value ? null : value
}

function clearSelection() {
  model.value = null
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2" data-testid="mood-selector">
    <span class="mr-1 text-xs text-surface-500 dark:text-surface-400">
      {{ t('action_memo.mood.label') }}
    </span>
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      :data-testid="`mood-${opt.value.toLowerCase()}`"
      class="flex items-center gap-1 rounded-full px-3 py-1 text-sm transition-shadow hover:shadow-sm"
      :class="[
        opt.badgeClass,
        model === opt.value
          ? 'ring-2 ring-primary ring-offset-1 dark:ring-offset-surface-900'
          : 'opacity-70 hover:opacity-100',
      ]"
      :aria-pressed="model === opt.value"
      @click="select(opt.value)"
    >
      <span class="text-base leading-none">{{ opt.emoji }}</span>
      <span>{{ t(opt.i18nKey) }}</span>
    </button>
    <button
      type="button"
      data-testid="mood-none"
      class="rounded-full px-3 py-1 text-xs text-surface-500 underline-offset-2 hover:underline dark:text-surface-400"
      @click="clearSelection"
    >
      {{ t('action_memo.mood.none') }}
    </button>
  </div>
</template>
