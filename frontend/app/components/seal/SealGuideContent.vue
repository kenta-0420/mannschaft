<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>

function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const previewSteps = computed<string[]>(() => resolveSteps('settings.seal_guide.preview.steps'))
const regenerateSteps = computed<string[]>(() => resolveSteps('settings.seal_guide.regenerate.steps'))
const defaultsSteps = computed<string[]>(() => resolveSteps('settings.seal_guide.defaults.steps'))
const historySteps = computed<string[]>(() => resolveSteps('settings.seal_guide.history.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('settings.seal_guide.description') }}
    </p>

    <!-- カード1: 印鑑プレビュー -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
        >
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('settings.seal_guide.preview.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('settings.seal_guide.preview.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in previewSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 印鑑の再生成 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400"
        >
          <i class="pi pi-refresh text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('settings.seal_guide.regenerate.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('settings.seal_guide.regenerate.body') }}
          </p>
          <ol class="mb-3 list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in regenerateSteps" :key="i">{{ step }}</li>
          </ol>
          <div class="rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-800">
            <p class="text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mr-1 text-blue-500" />
              {{ t('settings.seal_guide.regenerate.note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: デフォルト設定 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400"
        >
          <i class="pi pi-cog text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('settings.seal_guide.defaults.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('settings.seal_guide.defaults.body') }}
          </p>
          <ol class="mb-3 list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in defaultsSteps" :key="i">{{ step }}</li>
          </ol>
          <div class="rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-800">
            <p class="text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mr-1 text-blue-500" />
              {{ t('settings.seal_guide.defaults.note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 押印履歴 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400"
        >
          <i class="pi pi-history text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('settings.seal_guide.history.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('settings.seal_guide.history.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in historySteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
