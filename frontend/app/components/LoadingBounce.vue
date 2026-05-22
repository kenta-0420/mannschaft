<template>
  <div class="flex gap-1">
    <span
      v-for="(char, i) in 'loading'"
      :key="i"
      class="text-sm font-medium text-surface-500 dark:text-surface-400 inline-block"
      :style="{ animation: `bounce-up 2.0s ${i * 0.25}s infinite` }"
      >{{ char }}</span
    >
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
