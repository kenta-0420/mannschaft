<script setup lang="ts">
// props なし
</script>

<template>
  <div class="space-y-3">
    <div v-for="i in 3" :key="i" class="flex items-center gap-3 rounded-lg border border-surface-200 p-3">
      <Skeleton shape="circle" size="2rem" />
      <div class="flex-1 space-y-2">
        <Skeleton height="0.875rem" width="60%" />
        <Skeleton height="0.75rem" width="40%" />
      </div>
      <Skeleton height="1.5rem" width="4rem" />
    </div>
  </div>
</template>
