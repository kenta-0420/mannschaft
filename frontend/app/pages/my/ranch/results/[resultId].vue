<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const authStore = useAuthStore()
const generation = ref(0)
// 旧commandの本人bindingを維持し、私的画面だけを新しい本人scopeへ再生成する。
watch(() => authStore.user?.id ?? null, () => { generation.value += 1 }, { flush: 'sync' })
const ownerKey = computed(() => `${authStore.user?.id ?? 'anonymous'}:${generation.value}`)
</script>
<template>
 <RanchResultContent v-if="authStore.user" :key="ownerKey" />
</template>
