<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
  loading?: boolean
  colSpan?: 1 | 2 | 3
  refreshable?: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()
</script>

<template>
  <div
    class="rounded-xl border border-surface-300 bg-surface-0 p-4 shadow-sm transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
    :class="{
      'col-span-1': !colSpan || colSpan === 1,
      'md:col-span-2': colSpan === 2,
      'md:col-span-3': colSpan === 3,
    }"
  >
    <!-- ヘッダー -->
    <div class="mb-3 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <i v-if="icon" :class="icon" class="text-primary" />
        <h3 class="text-[22px] font-semibold text-surface-700 dark:text-surface-200">
          {{ title }}
        </h3>
      </div>
      <Button
        v-if="refreshable"
        icon="pi pi-refresh"
        text
        rounded
        size="small"
        :loading="loading"
        @click="emit('refresh')"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-3">
      <Skeleton height="1.5rem" />
      <Skeleton height="1.5rem" width="80%" />
      <Skeleton height="1.5rem" width="60%" />
    </div>

    <!-- コンテンツ -->
    <div v-else>
      <slot />
    </div>
  </div>
</template>
