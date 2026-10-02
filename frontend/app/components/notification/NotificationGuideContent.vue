<template>
  <div class="space-y-4">
    <!-- 概要 -->
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ $t('notification.guide.description') }}
    </p>

    <!-- セクション1: 通知を読む -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-bell text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('notification.guide.read.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('notification.guide.read.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">1</span>
              <span>{{ $t('notification.guide.read.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">2</span>
              <span>{{ $t('notification.guide.read.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">3</span>
              <span>{{ $t('notification.guide.read.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: 絞り込み -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('notification.guide.filter.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('notification.guide.filter.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">1</span>
              <span>{{ $t('notification.guide.filter.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">2</span>
              <span>{{ $t('notification.guide.filter.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">3</span>
              <span>{{ $t('notification.guide.filter.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: スヌーズ -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-clock text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('notification.guide.snooze.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('notification.guide.snooze.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">1</span>
              <span>{{ $t('notification.guide.snooze.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">2</span>
              <span>{{ $t('notification.guide.snooze.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">3</span>
              <span>{{ $t('notification.guide.snooze.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: 確認通知 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">
          <i class="pi pi-check-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('notification.guide.confirm.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('notification.guide.confirm.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-orange-100 text-xs font-semibold text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">1</span>
              <span>{{ $t('notification.guide.confirm.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-orange-100 text-xs font-semibold text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">2</span>
              <span>{{ $t('notification.guide.confirm.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-orange-100 text-xs font-semibold text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">3</span>
              <span>{{ $t('notification.guide.confirm.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション5: 緊急休業・一括既読 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400">
          <i class="pi pi-exclamation-triangle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('notification.guide.urgent.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('notification.guide.urgent.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-red-100 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400">1</span>
              <span>{{ $t('notification.guide.urgent.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-red-100 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400">2</span>
              <span>{{ $t('notification.guide.urgent.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-red-100 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400">3</span>
              <span>{{ $t('notification.guide.urgent.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
