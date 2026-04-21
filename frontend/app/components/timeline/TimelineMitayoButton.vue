<script setup lang="ts">
defineProps<{
  mitayo: boolean
  mitayoCount: number
  loading?: boolean
}>()

defineEmits<{
  toggle: []
}>()
</script>

<template>
  <button
    :class="[
      'flex items-center gap-1 rounded-full px-3 py-1 text-sm transition-colors',
      mitayo
        ? 'bg-primary-100 text-primary-600 dark:bg-primary-900 dark:text-primary-300'
        : 'text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-800',
    ]"
    :disabled="loading"
    @click="$emit('toggle')"
  >
    <span>{{ mitayo ? '✅' : '👀' }}</span>
    <span>{{ $t('timeline.mitayo') }}</span>
    <span v-if="mitayoCount > 0" class="font-medium">{{ mitayoCount }}</span>
  </button>
</template>
