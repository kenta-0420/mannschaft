<script setup lang="ts">
defineProps<{
  icon?: string
  message: string
  /** 2行目のヒント（薄字）。指定時のみ表示する。 */
  subMessage?: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-8 text-surface-400">
    <i :class="icon ?? 'pi pi-inbox'" class="mb-2 text-3xl" />
    <p class="text-sm">{{ message }}</p>
    <p v-if="subMessage" class="mt-1 max-w-md text-center text-xs text-surface-400">
      {{ subMessage }}
    </p>
    <div v-if="$slots.action" class="mt-3">
      <slot name="action" />
    </div>
  </div>
</template>
