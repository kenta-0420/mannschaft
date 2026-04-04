<script setup lang="ts">
withDefaults(defineProps<{ size?: string }>(), { size: '48px' })
</script>

<template>
  <div class="flex min-h-[calc(100vh-8rem)] flex-col items-center justify-center gap-4 pb-[15vh]">
    <ProgressSpinner :style="{ width: size, height: size }" />
    <div class="flex gap-1">
      <span
        v-for="(char, i) in 'loading'"
        :key="i"
        class="text-sm font-medium text-surface-500 dark:text-surface-400 inline-block"
        :style="{ animation: `bounce-up 2.0s ${i * 0.25}s infinite` }"
        >{{ char }}</span
      >
    </div>
  </div>
</template>

<style>
@keyframes bounce-up {
  0%,
  12.5%,
  100% {
    transform: translateY(0);
    color: inherit;
  }
  6.25% {
    transform: translateY(-8px);
    color: var(--p-primary-color);
  }
}
</style>
