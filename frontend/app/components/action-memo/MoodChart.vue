<script setup lang="ts">
import type { Mood, MoodStatsResponse } from '~/types/actionMemo'

/**
 * F02.5 気分集計バーチャート（Phase 4）。
 *
 * <p>CSS ベースのシンプルな横棒グラフ。Chart.js 等の重い依存は追加しない。
 * 5 段階の気分ごとにバー + パーセント + 絵文字を表示する。</p>
 */

const props = defineProps<{
  stats: MoodStatsResponse
}>()

const { t } = useI18n()

interface MoodBarItem {
  mood: Mood
  emoji: string
  i18nKey: string
  barClass: string
  count: number
  percent: number
}

const moodOrder: Mood[] = ['GREAT', 'GOOD', 'OK', 'TIRED', 'BAD']

const moodConfig: Record<Mood, { emoji: string; i18nKey: string; barClass: string }> = {
  GREAT: {
    emoji: '😄',
    i18nKey: 'action_memo.mood.great',
    barClass: 'bg-emerald-500',
  },
  GOOD: {
    emoji: '🙂',
    i18nKey: 'action_memo.mood.good',
    barClass: 'bg-teal-500',
  },
  OK: {
    emoji: '😐',
    i18nKey: 'action_memo.mood.ok',
    barClass: 'bg-surface-400',
  },
  TIRED: {
    emoji: '😩',
    i18nKey: 'action_memo.mood.tired',
    barClass: 'bg-orange-500',
  },
  BAD: {
    emoji: '😞',
    i18nKey: 'action_memo.mood.bad',
    barClass: 'bg-rose-500',
  },
}

const bars = computed<MoodBarItem[]>(() => {
  const total = props.stats.total || 0
  return moodOrder.map((mood) => {
    const count = props.stats.distribution[mood] ?? 0
    const percent = total > 0 ? Math.round((count / total) * 100) : 0
    return {
      mood,
      ...moodConfig[mood],
      count,
      percent,
    }
  })
})
</script>

<template>
  <div
    class="flex flex-col gap-2 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
    data-testid="mood-chart"
  >
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.mood_stats.title') }}
      </h3>
      <span class="text-xs text-surface-500 dark:text-surface-400">
        {{ t('action_memo.mood_stats.total') }}: {{ stats.total }}
      </span>
    </div>

    <div class="flex flex-col gap-1.5">
      <div
        v-for="bar in bars"
        :key="bar.mood"
        class="flex items-center gap-2"
        :data-testid="`mood-bar-${bar.mood.toLowerCase()}`"
      >
        <span class="w-6 text-center text-base">{{ bar.emoji }}</span>
        <span class="w-12 text-xs text-surface-600 dark:text-surface-300">
          {{ t(bar.i18nKey) }}
        </span>
        <div class="h-4 flex-1 overflow-hidden rounded-full bg-surface-100 dark:bg-surface-700">
          <div
            class="h-full rounded-full transition-all duration-500"
            :class="bar.barClass"
            :style="{ width: `${bar.percent}%` }"
          />
        </div>
        <span class="w-10 text-right text-xs text-surface-500 dark:text-surface-400">
          {{ bar.percent }}%
        </span>
      </div>
    </div>
  </div>
</template>
