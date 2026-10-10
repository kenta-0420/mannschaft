<script setup lang="ts">
/**
 * チーム加盟の設定画面の使い方ガイド本体（「色付き丸アイコン＋カード」方式）。
 * 申請の受付・チームグループ・受付停止の3機能を案内する。i18n: teamAffiliationGuide.*
 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

// 連番ステップ（step1, step2...）を順序付き配列へ正規化する（tm() の値を t() で個別解決）。
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map(k => t(`${key}.${k}`))
}

const acceptSteps = computed<string[]>(() => resolveSteps('teamAffiliationGuide.accept.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('teamAffiliationGuide.description') }}
    </p>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-inbox text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('teamAffiliationGuide.accept.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('teamAffiliationGuide.accept.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in acceptSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-sitemap text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('teamAffiliationGuide.groups.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('teamAffiliationGuide.groups.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('teamAffiliationGuide.groups.mode_off') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('teamAffiliationGuide.groups.mode_optional') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('teamAffiliationGuide.groups.mode_required') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-pause text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('teamAffiliationGuide.stop.title') }}
          </h2>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="flex items-start gap-2 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mt-0.5" aria-hidden="true" />
              <span>{{ t('teamAffiliationGuide.stop.body') }}</span>
            </p>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
