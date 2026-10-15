<script setup lang="ts">
const { t } = useI18n()

const guideCards = [
  {
    key: 'searchConditions',
    icon: 'pi pi-filter',
    iconClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  },
  {
    key: 'results',
    icon: 'pi pi-search',
    iconClass: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
  },
  {
    key: 'supporter',
    icon: 'pi pi-heart',
    iconClass: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400',
  },
  {
    key: 'createAndMove',
    icon: 'pi pi-plus-circle',
    iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400',
  },
] as const
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('teamHub.guide.description') }}
    </p>

    <SectionCard v-for="card in guideCards" :key="card.key">
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full"
          :class="card.iconClass"
        >
          <i :class="[card.icon, 'text-xl']" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`teamHub.guide.${card.key}.title`) }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`teamHub.guide.${card.key}.body`) }}
          </p>
          <ol
            class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300"
          >
            <li>{{ t(`teamHub.guide.${card.key}.steps.step1`) }}</li>
            <li>{{ t(`teamHub.guide.${card.key}.steps.step2`) }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
