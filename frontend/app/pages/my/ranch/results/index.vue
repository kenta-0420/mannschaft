<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const authStore = useAuthStore()
const ownerGeneration = ref(0)
watch(() => authStore.user?.id ?? null, () => { ownerGeneration.value += 1 }, { flush: 'sync' })
</script>
<template>
 <RanchResultsContent v-if="authStore.user" :key="`${authStore.user.id}:${ownerGeneration}`" />
</template>
