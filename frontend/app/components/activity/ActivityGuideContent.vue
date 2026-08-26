<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>

/**
 * 連番ステップ（step1, step2...）を順序付き配列へ正規化する。
 * tm() の値を t() で個別解決することで、ロケール切り替えに追従する。
 */
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const addSteps = computed<string[]>(() => resolveSteps('activity.guide.add.steps'))
</script>

<template>
  <div class="space-y-4">
    <!-- 導入文 -->
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('activity.guide.description') }}
    </p>

    <!-- カード1: 記録を見る -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-history text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('activity.guide.list.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('activity.guide.list.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 記録を追加する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('activity.guide.add.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('activity.guide.add.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in addSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: テンプレートが必要なとき -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-file-edit text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('activity.guide.template.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('activity.guide.template.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 公開範囲 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('activity.guide.visibility.title') }}
          </h2>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ t('activity.guide.visibility.members_only_label') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ t('activity.guide.visibility.members_only_desc') }}
              </p>
            </div>
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ t('activity.guide.visibility.public_label') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ t('activity.guide.visibility.public_desc') }}
              </p>
            </div>
          </div>
          <p class="mt-2 text-xs text-surface-500 dark:text-surface-400">
            {{ t('activity.guide.visibility.note') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
