<script setup lang="ts">
/**
 * 申請事前拒否リストの使い方ガイドの本体コンテンツ。
 *
 * 「色付き丸アイコン＋SectionCard」方式で、拒否リストとは何か・一覧の見方・
 * 拒否解除のしかたの3点を案内する。
 * i18n: contact_request_blocks_help.*
 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

// i18n の連番ステップ（step1, step2...）を順序付き配列として取り出す。
// tm() は locale message を返すため、値は t() で個別解決して string 配列に正規化する。
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const unblockSteps = computed<string[]>(() => resolveSteps('contact_request_blocks_help.unblock.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('contact_request_blocks_help.description') }}
    </p>

    <!-- 申請事前拒否リストとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-ban text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('contact_request_blocks_help.what.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_request_blocks_help.what.body') }}
          </p>
          <div class="flex items-start gap-2 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <i class="pi pi-info-circle mt-0.5 text-blue-500" aria-hidden="true" />
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('contact_request_blocks_help.what.note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- 登録されている人の見方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-list text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('contact_request_blocks_help.list.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_request_blocks_help.list.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_request_blocks_help.list.item_name') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_request_blocks_help.list.item_handle') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_request_blocks_help.list.item_date') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- 拒否を解除するには -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-undo text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('contact_request_blocks_help.unblock.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_request_blocks_help.unblock.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in unblockSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
