<script setup lang="ts">
definePageMeta({
  middleware: ['auth'],
})
</script>

<template>
  <div>
    <h2 class="mb-4 text-2xl font-semibold">
      ダッシュボード
    </h2>
    <p class="text-muted-color">
      ここにダッシュボードウィジェットが配置されます。
    </p>
  </div>
</template>
