<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>

function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const searchSteps = computed<string[]>(() => resolveSteps('contact_privacy.guide.search.steps'))
const approvalSteps = computed<string[]>(() => resolveSteps('contact_privacy.guide.approval.steps'))
const saveSteps = computed<string[]>(() => resolveSteps('contact_privacy.guide.save.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('contact_privacy.guide.description') }}
    </p>

    <!-- カード1: @ハンドル検索 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
        >
          <i class="pi pi-search text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_privacy.guide.search.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.search.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in searchSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 連絡先追加の承認 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400"
        >
          <i class="pi pi-user-plus text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_privacy.guide.approval.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.approval.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in approvalSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: DMを受信できる相手 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400"
        >
          <i class="pi pi-comments text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_privacy.guide.dm.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.dm.body') }}
          </p>
          <p class="mb-2 text-sm text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.dm.options_intro') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_privacy.guide.dm.anyone') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_privacy.guide.dm.team_members_only') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-green-500" aria-hidden="true" />
              <span>{{ t('contact_privacy.guide.dm.contacts_only') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: オンライン状態の公開範囲 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400"
        >
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_privacy.guide.online.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.online.body') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
              <li>{{ t('contact_privacy.guide.online.nobody') }}</li>
              <li>{{ t('contact_privacy.guide.online.contacts_only') }}</li>
              <li>{{ t('contact_privacy.guide.online.everyone') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 設定の保存 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400"
        >
          <i class="pi pi-save text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_privacy.guide.save.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_privacy.guide.save.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in saveSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
