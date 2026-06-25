<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('schedule.calendar_guide.description') }}
    </p>

    <!-- カード1: マイカレンダーとは（blue, pi-calendar） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-calendar text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">{{ t('schedule.calendar_guide.what.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('schedule.calendar_guide.what.body') }}</p>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 予定を追加する（green, pi-plus-circle） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('schedule.calendar_guide.add.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('schedule.calendar_guide.add.body') }}</p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('schedule.calendar_guide.add.step1') }}</li>
            <li>{{ t('schedule.calendar_guide.add.step2') }}</li>
            <li>{{ t('schedule.calendar_guide.add.step3') }}</li>
            <li>{{ t('schedule.calendar_guide.add.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 表示を絞り込む（violet, pi-filter） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">{{ t('schedule.calendar_guide.filter.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('schedule.calendar_guide.filter.body') }}</p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
              <li class="flex items-start gap-2">
                <span class="mt-0.5 inline-block h-2 w-2 shrink-0 rounded-full bg-green-500" />
                <span>{{ t('schedule.calendar_guide.filter.personal') }}</span>
              </li>
              <li class="flex items-start gap-2">
                <span class="mt-0.5 inline-block h-2 w-2 shrink-0 rounded-full bg-indigo-500" />
                <span>{{ t('schedule.calendar_guide.filter.team_org') }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: ガントビュー（amber, pi-bars） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-bars text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">{{ t('schedule.calendar_guide.gantt.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('schedule.calendar_guide.gantt.body') }}</p>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 予定の詳細・出欠回答（teal, pi-info-circle） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-info-circle text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">{{ t('schedule.calendar_guide.detail.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('schedule.calendar_guide.detail.body') }}</p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('schedule.calendar_guide.detail.item_edit') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('schedule.calendar_guide.detail.item_attendance') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('schedule.calendar_guide.detail.item_match') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
