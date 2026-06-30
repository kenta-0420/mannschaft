<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}
const toggleSteps = computed<string[]>(() => resolveSteps('module_settings_guide.toggle.steps'))
const restrictionItems = computed<string[]>(() =>
  resolveSteps('module_settings_guide.restrictions.items'),
)
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('module_settings_guide.description') }}
    </p>

    <!-- カード1: 機能設定とは (blue / pi-puzzle) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-puzzle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.what.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.what.body') }}</p>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 機能を ON/OFF する (green / pi-sliders-h) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-sliders-h text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.toggle.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.toggle.body') }}</p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in toggleSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 有効な機能数の確認 (violet / pi-chart-bar) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-chart-bar text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.count.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.count.body') }}</p>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 使えないスイッチがあるとき (rose / pi-lock) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 dark:bg-rose-900/30 dark:text-rose-400">
          <i class="pi pi-lock text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.restrictions.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.restrictions.body') }}</p>
          <ul class="list-disc space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(item, i) in restrictionItems" :key="i">{{ item }}</li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 機能が表示されないとき (amber / pi-info-circle) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-info-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.empty.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.empty.body') }}</p>
        </div>
      </div>
    </SectionCard>

    <!-- カード6: 設定できる人 (teal / pi-shield) -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('module_settings_guide.permission.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('module_settings_guide.permission.body') }}</p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
