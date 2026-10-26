<script setup lang="ts">
defineProps<{
  title: string
  items: Array<{ id: number; userId: number; createdAt: string; reason: string }>
  emptyMessage: string
  linkTo: string
}>()

const { relativeTime } = useRelativeTime()
</script>

<template>
  <div
    class="rounded-xl border border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-800"
  >
    <div
      class="flex items-center justify-between border-b border-surface-100 px-4 py-3 dark:border-surface-600"
    >
      <span class="text-sm font-semibold">{{ title }}</span>
      <NuxtLink :to="linkTo" class="text-xs text-primary hover:underline">すべて表示</NuxtLink>
    </div>
    <div v-if="items.length > 0" class="divide-y divide-surface-100 dark:divide-surface-700">
      <div v-for="r in items" :key="r.id" class="px-4 py-3">
        <div class="flex items-start justify-between gap-2">
          <p class="min-w-0 flex-1 truncate text-sm text-surface-700 dark:text-surface-200">
            ユーザー #{{ r.userId }}
          </p>
          <span class="shrink-0 text-[11px] text-surface-400">{{
            relativeTime(r.createdAt)
          }}</span>
        </div>
        <p class="mt-0.5 line-clamp-1 text-xs text-surface-500">{{ r.reason }}</p>
      </div>
    </div>
    <div v-else class="px-4 py-8 text-center text-sm text-surface-400">
      <i class="pi pi-check-circle mb-2 text-2xl text-green-400" />
      <p>{{ emptyMessage }}</p>
    </div>
  </div>
</template>
