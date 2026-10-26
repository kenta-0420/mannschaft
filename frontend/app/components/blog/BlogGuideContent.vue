<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}
const createSteps = computed<string[]>(() => resolveSteps('blog_guide.create.steps'))
const publishSteps = computed<string[]>(() => resolveSteps('blog_guide.publish.steps'))
const settingsSteps = computed<string[]>(() => resolveSteps('blog_guide.settings.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('blog_guide.description') }}
    </p>

    <!-- カード1: ブログとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-book text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('blog_guide.what.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('blog_guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 記事を書く -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-pencil text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('blog_guide.create.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('blog_guide.create.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in createSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 記事を公開する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('blog_guide.publish.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('blog_guide.publish.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in publishSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 記事を管理する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-list text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('blog_guide.manage.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('blog_guide.manage.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              {{ t('blog_guide.manage.item_edit') }}
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              {{ t('blog_guide.manage.item_delete') }}
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              {{ t('blog_guide.manage.item_count') }}
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              {{ t('blog_guide.manage.item_all') }}
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: ブログ設定 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-cog text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('blog_guide.settings.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('blog_guide.settings.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in settingsSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
