<script setup lang="ts">
/**
 * 村参加申請ページの使い方を案内するカード本体。
 * VillageGuideContent.vue を金型に、申請の出し方・審査・結果確認を説明する。
 */
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
  { key: 'apply', icon: 'pi pi-send', iconClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'status', icon: 'pi pi-clock', iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
  { key: 'review', icon: 'pi pi-check-square', iconClass: 'bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400' },
]
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('village.joinRequestGuide.description') }}
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
            {{ t(`village.joinRequestGuide.${card.key}.title`) }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`village.joinRequestGuide.${card.key}.body`) }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in resolveSteps(`village.joinRequestGuide.${card.key}.steps`)" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
