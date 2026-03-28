<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <h1 class="mb-6 text-2xl font-bold">メンション</h1>
    <MentionList />
  </div>
</template>
