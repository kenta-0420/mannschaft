<script setup lang="ts">
const props = defineProps<{
  title: string
  size?: 'lg' | 'sm'
}>()

const titleClass = computed(() =>
  props.size === 'sm' ? 'text-2xl font-bold' : 'text-4xl font-bold',
)
</script>

<template>
  <div class="mb-6 flex items-end gap-3">
    <h1 :class="titleClass">{{ title }}</h1>
    <slot />
  </div>
</template>
