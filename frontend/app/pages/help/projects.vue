<script setup lang="ts">
/**
 * プロジェクト機能 使い方説明ページ。
 *
 * マイルストーン・ゲート・進捗バー・個人/チームの違い等を解説する。
 */

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const router = useRouter()

useSeoMeta({
  title: () => `${t('project.guide.page_title')} | Mannschaft`,
  description: () => t('project.guide.description'),
})
</script>

<template>
  <main class="mx-auto w-full max-w-[860px] px-4 py-8 md:px-6">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center gap-3">
      <Button
        icon="pi pi-arrow-left"
        text
        rounded
        :aria-label="t('common.button.back')"
        @click="router.back()"
      />
      <h1 class="text-2xl font-bold text-surface-900 dark:text-white">
        {{ t('project.guide.page_title') }}
      </h1>
    </div>

    <p class="mb-8 text-surface-600 dark:text-surface-300">
      {{ t('project.guide.description') }}
    </p>

    <!-- セクション1: プロジェクトとは -->
    <section class="mb-6">
      <SectionCard>
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
            <i class="pi pi-briefcase text-xl" aria-hidden="true" />
          </div>
          <div>
            <h2 class="mb-2 text-lg font-semibold">
              {{ t('project.guide.what.title') }}
            </h2>
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('project.guide.what.body') }}
            </p>
          </div>
        </div>
      </SectionCard>
    </section>

    <!-- セクション2: プロジェクトの作り方 -->
    <section class="mb-6">
      <SectionCard>
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
            <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
          </div>
          <div class="w-full">
            <h2 class="mb-2 text-lg font-semibold">
              {{ t('project.guide.create.title') }}
            </h2>
            <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('project.guide.create.body') }}
            </p>
            <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
              <li class="flex items-center gap-2">
                <i class="pi pi-check text-green-500" aria-hidden="true" />
                <span>{{ t('project.guide.create.item_title') }}</span>
              </li>
              <li class="flex items-center gap-2">
                <i class="pi pi-check text-green-500" aria-hidden="true" />
                <span>{{ t('project.guide.create.item_emoji') }}</span>
              </li>
              <li class="flex items-center gap-2">
                <i class="pi pi-check text-green-500" aria-hidden="true" />
                <span>{{ t('project.guide.create.item_color') }}</span>
              </li>
              <li class="flex items-center gap-2">
                <i class="pi pi-check text-green-500" aria-hidden="true" />
                <span>{{ t('project.guide.create.item_due_date') }}</span>
              </li>
            </ul>
          </div>
        </div>
      </SectionCard>
    </section>

    <!-- セクション3: マイルストーンとゲート -->
    <section class="mb-6">
      <SectionCard>
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
            <i class="pi pi-flag text-xl" aria-hidden="true" />
          </div>
          <div>
            <h2 class="mb-2 text-lg font-semibold">
              {{ t('project.guide.milestone.title') }}
            </h2>
            <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('project.guide.milestone.body') }}
            </p>
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-medium text-surface-700 dark:text-surface-200">
                {{ t('project.guide.milestone.completion_mode_label') }}
              </p>
              <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
                <li>
                  <span class="font-medium">{{ t('project.guide.milestone.mode_auto') }}</span>
                  — {{ t('project.guide.milestone.mode_auto_desc') }}
                </li>
                <li>
                  <span class="font-medium">{{ t('project.guide.milestone.mode_manual') }}</span>
                  — {{ t('project.guide.milestone.mode_manual_desc') }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </SectionCard>
    </section>

    <!-- セクション4: 進捗の見方 -->
    <section class="mb-6">
      <SectionCard>
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
            <i class="pi pi-chart-bar text-xl" aria-hidden="true" />
          </div>
          <div>
            <h2 class="mb-2 text-lg font-semibold">
              {{ t('project.guide.progress.title') }}
            </h2>
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('project.guide.progress.body') }}
            </p>
          </div>
        </div>
      </SectionCard>
    </section>

    <!-- セクション5: 個人プロジェクトとチームプロジェクト -->
    <section class="mb-6">
      <SectionCard>
        <div class="flex items-start gap-4">
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
            <i class="pi pi-users text-xl" aria-hidden="true" />
          </div>
          <div>
            <h2 class="mb-2 text-lg font-semibold">
              {{ t('project.guide.scope.title') }}
            </h2>
            <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('project.guide.scope.body') }}
            </p>
            <div class="grid gap-3 sm:grid-cols-2">
              <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
                <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                  {{ t('project.guide.scope.personal_title') }}
                </p>
                <p class="text-xs text-surface-500 dark:text-surface-400">
                  {{ t('project.guide.scope.personal_desc') }}
                </p>
              </div>
              <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
                <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                  {{ t('project.guide.scope.team_title') }}
                </p>
                <p class="text-xs text-surface-500 dark:text-surface-400">
                  {{ t('project.guide.scope.team_desc') }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>
    </section>

    <!-- フッター：ページに戻る -->
    <div class="mt-8 flex justify-center">
      <Button
        :label="t('project.guide.back_to_projects')"
        icon="pi pi-arrow-left"
        text
        @click="router.back()"
      />
    </div>
  </main>
</template>
