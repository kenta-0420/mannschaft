<script setup lang="ts">
defineProps<{
  title?: string
}>()
</script>

<template>
  <div
    class="rounded-xl border border-surface-400 bg-surface-0 p-6 dark:border-surface-500 dark:bg-surface-800"
  >
    <h2 v-if="title" class="mb-4 text-lg font-semibold">{{ title }}</h2>
    <slot />
  </div>
</template>
