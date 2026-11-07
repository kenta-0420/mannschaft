<script setup lang="ts">
/** 学校出欠の教員用画面で権限が無いときに表示する「権限がありません」パネル（AC-18）。 */
</script>

<template>
  <main class="flex-1 p-4 max-w-2xl mx-auto w-full" data-testid="school-attendance-forbidden" role="alert">
    <div class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800 p-6 text-center">
      <i class="pi pi-lock text-3xl text-surface-400 mb-3" />
      <h2 class="text-base font-semibold mb-2">
        {{ $t('school.attendance.forbidden.title') }}
      </h2>
      <p class="text-sm text-surface-500 m-0">
        {{ $t('school.attendance.forbidden.description') }}
      </p>
    </div>
  </main>
</template>
