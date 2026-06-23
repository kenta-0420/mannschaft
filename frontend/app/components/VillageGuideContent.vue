<script setup lang="ts">
/** 村ページの使い方を案内するカード本体。ProjectGuideContent を金型に、村の参加操作と9タブの使い方を説明する。 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>
/** 連番ステップ（step1, step2...）を順序付き配列へ正規化（tm() の値を t() で個別解決）。 */
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map(k => t(`${key}.${k}`))
}

interface GuideCard {
  key: string
  icon: string
  iconClass: string
}

const cards: GuideCard[] = [
  { key: 'join', icon: 'pi pi-sign-in', iconClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'bulletin', icon: 'pi pi-megaphone', iconClass: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' },
  { key: 'timeline', icon: 'pi pi-comments', iconClass: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400' },
  { key: 'lobby', icon: 'pi pi-comment', iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
  { key: 'members', icon: 'pi pi-users', iconClass: 'bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400' },
  { key: 'calendar', icon: 'pi pi-calendar', iconClass: 'bg-rose-100 text-rose-600 dark:bg-rose-900/30 dark:text-rose-400' },
  { key: 'events', icon: 'pi pi-star', iconClass: 'bg-cyan-100 text-cyan-600 dark:bg-cyan-900/30 dark:text-cyan-400' },
  { key: 'meetup', icon: 'pi pi-calendar-plus', iconClass: 'bg-indigo-100 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-400' },
  { key: 'chronicle', icon: 'pi pi-book', iconClass: 'bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400' },
]
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('village.guide.description') }}
    </p>

    <SectionCard v-for="card in cards" :key="card.key">
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full"
          :class="card.iconClass"
        >
          <i :class="`${card.icon} text-xl`" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`village.guide.${card.key}.title`) }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`village.guide.${card.key}.body`) }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in resolveSteps(`village.guide.${card.key}.steps`)" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
