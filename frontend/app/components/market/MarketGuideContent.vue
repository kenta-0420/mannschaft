<script setup lang="ts">
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((step) => t(`${key}.${step}`))
}

const whatSteps = computed<string[]>(() => resolveSteps('market.market_guide.what.steps'))
const searchSteps = computed<string[]>(() => resolveSteps('market.market_guide.search.steps'))
const applySteps = computed<string[]>(() => resolveSteps('market.market_guide.apply.steps'))
const personalCreateSteps = computed<string[]>(() =>
  resolveSteps('market.market_guide.personal_create.steps'),
)
const visibilitySteps = computed<string[]>(() =>
  resolveSteps('market.market_guide.visibility.steps'),
)
const teamOrganizationSteps = computed<string[]>(() =>
  resolveSteps('market.market_guide.team_organization.steps'),
)
const cancellationSteps = computed<string[]>(() =>
  resolveSteps('market.market_guide.cancellation.steps'),
)
</script>

<template>
  <div class="space-y-4" data-testid="market-guide-content">
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
        >
          <i class="pi pi-tag text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('market.market_guide.what.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.what.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in whatSteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400"
        >
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('market.market_guide.search.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.search.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in searchSteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400"
        >
          <i class="pi pi-check-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('market.market_guide.apply.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.apply.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in applySteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400"
        >
          <i class="pi pi-pencil text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('market.market_guide.personal_create.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.personal_create.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in personalCreateSteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400"
        >
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('market.market_guide.visibility.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.visibility.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in visibilitySteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 dark:bg-rose-900/30 dark:text-rose-400"
        >
          <i class="pi pi-users text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('market.market_guide.team_organization.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.team_organization.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in teamOrganizationSteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400"
        >
          <i class="pi pi-times-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('market.market_guide.cancellation.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('market.market_guide.cancellation.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, index) in cancellationSteps" :key="index">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
