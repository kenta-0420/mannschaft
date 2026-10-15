<script setup lang="ts">
/** 村一覧（さがす）ページの使い方を案内するカード本体。VillageGuideContent と同じ金型。 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>
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
  { key: 'search', icon: 'pi pi-search', iconClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'cards', icon: 'pi pi-th-large', iconClass: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' },
  { key: 'enter', icon: 'pi pi-sign-in', iconClass: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400' },
  { key: 'create', icon: 'pi pi-plus-circle', iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
]
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('village.listGuide.description') }}
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
            {{ t(`village.listGuide.${card.key}.title`) }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`village.listGuide.${card.key}.body`) }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in resolveSteps(`village.listGuide.${card.key}.steps`)" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
