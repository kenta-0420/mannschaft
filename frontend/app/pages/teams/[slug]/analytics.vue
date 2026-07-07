<script setup lang="ts">
// チームアクセス解析ページ
// BE APIが未実装のため、現在は「準備中」表示のみ
// 本実装は別途軍議・BEページビュー計測基盤の整備後に行う
definePageMeta({ layout: 'team', middleware: 'auth' })
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader :title="$t('teamSidebar.item.analytics')" />
    <div class="mt-12 flex flex-col items-center justify-center gap-4 text-center">
      <i class="pi pi-clock text-5xl text-surface-300 dark:text-surface-600" />
      <p class="text-xl font-semibold text-surface-700 dark:text-surface-200">
        {{ $t('comingSoon.title') }}
      </p>
      <p class="max-w-md text-sm text-surface-500 dark:text-surface-400">
        {{ $t('comingSoon.description') }}
      </p>
    </div>
  </div>
</template>
