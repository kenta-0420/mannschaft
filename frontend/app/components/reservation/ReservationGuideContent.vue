<script setup lang="ts">
/**
 * マイ予約ページの使い方を案内するカード本体。
 * 連番オブジェクト（steps/items）は tm で取得し配列化して描画する。
 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

function resolveList(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const overviewSteps = computed<string[]>(() => resolveList('reservation.guide.overview.steps'))
const statusItems = computed<string[]>(() => resolveList('reservation.guide.status.items'))
const emptySteps = computed<string[]>(() => resolveList('reservation.guide.empty.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('reservation.guide.description') }}
    </p>

    <!-- カード1: マイ予約とは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-calendar text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('reservation.guide.overview.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('reservation.guide.overview.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li
              v-for="(step, i) in overviewSteps"
              :key="i"
            >
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: ステータスの見方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-tag text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('reservation.guide.status.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('reservation.guide.status.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li
              v-for="(item, i) in statusItems"
              :key="i"
              class="flex items-center gap-2"
            >
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ item }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 予約が表示されないとき -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-info-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('reservation.guide.empty.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('reservation.guide.empty.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li
              v-for="(step, i) in emptySteps"
              :key="i"
            >
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
