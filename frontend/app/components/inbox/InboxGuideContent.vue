<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <div class="space-y-4">
    <!-- カード1: タブで通知を整理する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-inbox text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('inbox.guide.tabs.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('inbox.guide.tabs.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('inbox.guide.tabs.step1') }}</li>
            <li>{{ t('inbox.guide.tabs.step2') }}</li>
            <li>{{ t('inbox.guide.tabs.step3') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 優先度と種類で絞り込む -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('inbox.guide.filter.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('inbox.guide.filter.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('inbox.guide.filter.step1') }}</li>
            <li>{{ t('inbox.guide.filter.step2') }}</li>
            <li>{{ t('inbox.guide.filter.step3') }}</li>
            <li>{{ t('inbox.guide.filter.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: スヌーズで後で対応する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-clock text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('inbox.guide.snooze.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('inbox.guide.snooze.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('inbox.guide.snooze.step1') }}</li>
            <li>{{ t('inbox.guide.snooze.step2') }}</li>
            <li>{{ t('inbox.guide.snooze.step3') }}</li>
            <li>{{ t('inbox.guide.snooze.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: ラベルで分類・管理する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-tag text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('inbox.guide.label.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('inbox.guide.label.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('inbox.guide.label.step1') }}</li>
            <li>{{ t('inbox.guide.label.step2') }}</li>
            <li>{{ t('inbox.guide.label.step3') }}</li>
            <li>{{ t('inbox.guide.label.step4') }}</li>
            <li>{{ t('inbox.guide.label.step5') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 複数を一括処理する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-check-square text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('inbox.guide.bulk.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('inbox.guide.bulk.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('inbox.guide.bulk.step1') }}</li>
            <li>{{ t('inbox.guide.bulk.step2') }}</li>
            <li>{{ t('inbox.guide.bulk.step3') }}</li>
            <li>{{ t('inbox.guide.bulk.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
