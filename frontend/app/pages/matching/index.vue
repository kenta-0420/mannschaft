<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">対戦・交流を探す</h1></div>
    <MatchRequestList />
  </div>
</template>
