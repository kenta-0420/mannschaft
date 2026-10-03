<script setup lang="ts">
/**
 * 行動メモ ページの使い方説明モーダル。
 * v-model:visible で開閉を制御する。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('action_memo.guide.title')"
    class="w-full max-w-2xl"
    data-testid="action-memo-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <div class="space-y-4">
        <!-- 概要 -->
        <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
          {{ $t('action_memo.guide.description') }}
        </p>

        <!-- セクション1: メモを記録する -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
              <i class="pi pi-pencil text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('action_memo.guide.record.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('action_memo.guide.record.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">1</span>
                  <span>{{ $t('action_memo.guide.record.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">2</span>
                  <span>{{ $t('action_memo.guide.record.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">3</span>
                  <span>{{ $t('action_memo.guide.record.steps.step3') }}</span>
                </li>
              </ol>
            </div>
          </div>
        </SectionCard>

        <!-- セクション2: 過去のメモを見る -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
              <i class="pi pi-calendar text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('action_memo.guide.past.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('action_memo.guide.past.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">1</span>
                  <span>{{ $t('action_memo.guide.past.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">2</span>
                  <span>{{ $t('action_memo.guide.past.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-100 text-xs font-semibold text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">3</span>
                  <span>{{ $t('action_memo.guide.past.steps.step3') }}</span>
                </li>
              </ol>
            </div>
          </div>
        </SectionCard>

        <!-- セクション3: 振り返る -->
        <SectionCard>
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
              <i class="pi pi-chart-bar text-xl" aria-hidden="true" />
            </div>
            <div class="w-full">
              <h2 class="mb-2 text-lg font-semibold">
                {{ $t('action_memo.guide.review.title') }}
              </h2>
              <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
                {{ $t('action_memo.guide.review.body') }}
              </p>
              <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">1</span>
                  <span>{{ $t('action_memo.guide.review.steps.step1') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">2</span>
                  <span>{{ $t('action_memo.guide.review.steps.step2') }}</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-xs font-semibold text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">3</span>
                  <span>{{ $t('action_memo.guide.review.steps.step3') }}</span>
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
