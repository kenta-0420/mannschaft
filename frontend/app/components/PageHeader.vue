<script setup lang="ts">
defineProps<{
  title: string
}>()
</script>

<template>
  <div class="mb-6 flex items-end gap-3">
    <h1 class="text-4xl font-bold">{{ title }}</h1>
    <slot />
  </div>
</template>
