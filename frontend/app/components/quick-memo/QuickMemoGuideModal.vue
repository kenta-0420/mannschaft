<script setup lang="ts">
/**
 * ポイっとメモ ページの使い方説明モーダル。
 * v-model:visible で開閉を制御する。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('quick_memo.guide.title')"
    class="w-full max-w-2xl"
    data-testid="quick-memo-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <div class="space-y-4">
        <!-- 概要 -->
        <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
          {{ $t('quick_memo.guide.description') }}
        </p>

        <!-- セクション1: すぐに記録する -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
              <i class="pi pi-bolt text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('quick_memo.guide.capture.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('quick_memo.guide.capture.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">1</span>
                  <span>{{ $t('quick_memo.guide.capture.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">2</span>
                  <span>{{ $t('quick_memo.guide.capture.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">3</span>
                  <span>{{ $t('quick_memo.guide.capture.steps.step3') }}</span>
                </li>
              </ol>
            </div>
          </div>
        </SectionCard>

        <!-- セクション2: 未整理を整理する -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
              <i class="pi pi-sort-alt text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('quick_memo.guide.organize.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('quick_memo.guide.organize.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">1</span>
                  <span>{{ $t('quick_memo.guide.organize.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">2</span>
                  <span>{{ $t('quick_memo.guide.organize.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">3</span>
                  <span>{{ $t('quick_memo.guide.organize.steps.step3') }}</span>
                </li>
              </ol>
            </div>
          </div>
        </SectionCard>

        <!-- セクション3: 検索して探す -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
              <i class="pi pi-search text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('quick_memo.guide.search.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('quick_memo.guide.search.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">1</span>
                  <span>{{ $t('quick_memo.guide.search.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">2</span>
                  <span>{{ $t('quick_memo.guide.search.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">3</span>
                  <span>{{ $t('quick_memo.guide.search.steps.step3') }}</span>
                </li>
              </ol>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
