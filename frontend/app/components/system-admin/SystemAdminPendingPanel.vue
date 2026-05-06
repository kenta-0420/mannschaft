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
  <DashboardWidgetCard :title="title" icon="pi pi-clock" :to="linkTo" :scrollable="true">
    <div v-if="items.length > 0" class="divide-y divide-surface-100 dark:divide-surface-700">
      <div v-for="r in items" :key="r.id" class="py-3">
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
    <DashboardEmptyState v-else icon="pi pi-check-circle" :message="emptyMessage" />
  </DashboardWidgetCard>
</template>
