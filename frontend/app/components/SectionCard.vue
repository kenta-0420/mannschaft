<script setup lang="ts">
defineProps<{
  title?: string
}>()
</script>

<template>
  <div
    class="rounded-xl border-2 border-surface-400 bg-surface-0 p-6 dark:border-surface-500 dark:bg-surface-800"
  >
    <div v-if="$slots.header" class="mb-4">
      <slot name="header" />
    </div>
    <h2 v-else-if="title" class="mb-4 text-lg font-semibold">{{ title }}</h2>
    <slot />
  </div>
</template>
