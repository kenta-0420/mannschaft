<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const auth = useAuthStore()
let generation = 0
const accountKey = ref('')
watch(() => auth.user?.id ?? null, id => {
 generation += 1
 accountKey.value = id === null ? '' : `${id}:${generation}`
}, { immediate: true, flush: 'sync' })
</script>
<template><RanchPageContent v-if="accountKey" :key="accountKey" /></template>