<script setup lang="ts">
const { t, tm } = useI18n()
function resolveSteps(key: string): string[] {
  const raw = tm(key) as Record<string, string> | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((item) => t(`${key}.${item}`))
}
const cards = [
  {
    key: 'what',
    icon: 'pi pi-calendar',
    color: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  },
  {
    key: 'create',
    icon: 'pi pi-plus-circle',
    color: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
  },
  {
    key: 'switch',
    icon: 'pi pi-arrow-right-arrow-left',
    color: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400',
  },
  {
    key: 'summary',
    icon: 'pi pi-chart-bar',
    color: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400',
  },
  {
    key: 'trouble',
    icon: 'pi pi-info-circle',
    color: 'bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400',
  },
]
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('budget_guide.description') }}
    </p>
    <SectionCard v-for="card in cards" :key="card.key">
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full"
          :class="card.color"
        >
          <i :class="[card.icon, 'text-xl']" aria-hidden="true" />
        </div>
        <div class="min-w-0 flex-1">
          <h2 class="mb-2 text-lg font-semibold">{{ t(`budget_guide.${card.key}.title`) }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`budget_guide.${card.key}.body`) }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li
              v-for="(step, index) in resolveSteps(`budget_guide.${card.key}.steps`)"
              :key="index"
            >
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
