<script setup lang="ts">
defineProps<{
  count: number
}>()

const emit = defineEmits<{
  open: []
}>()
</script>

<template>
  <div
    v-if="count > 0"
    class="flex cursor-pointer items-center gap-1.5 border-b border-surface-100 dark:border-surface-800 bg-primary/5 px-4 py-1.5 text-xs text-primary hover:bg-primary/10"
    @click="emit('open')"
  >
    <i class="pi pi-comments text-xs" />
    <span>{{ $t('chat.thread.activeThreads', { count }) }}</span>
    <i class="pi pi-chevron-right ml-auto text-xs" />
  </div>
</template>
