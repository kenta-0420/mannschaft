<script setup lang="ts">
defineProps<{
  title?: string
}>()
</script>

<template>
  <div
    class="rounded-xl border border-surface-200 bg-surface-0 p-5 shadow-[var(--shadow-card)] transition-shadow duration-150 dark:border-surface-700 dark:bg-surface-800"
  >
    <div v-if="$slots.header" class="mb-3">
      <slot name="header" />
    </div>
    <h2 v-else-if="title" class="mb-3 text-lg font-semibold tracking-tight">{{ title }}</h2>
    <slot />
  </div>
</template>
