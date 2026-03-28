<script setup lang="ts">
definePageMeta({
  middleware: ['auth'],
})

onMounted(() => {
  navigateTo('/dashboard', { replace: true })
})
</script>

<template>
  <div />
</template>
