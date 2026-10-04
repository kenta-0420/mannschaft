<script setup lang="ts">
const props = withDefaults(defineProps<{ active?: boolean; collapsed?: boolean }>(), { active: true, collapsed: undefined })
const emit = defineEmits<{ 'update:collapsed': [collapsed: boolean] }>()
const authStore = useAuthStore()
const generation = ref(0)
// 旧commandの本人bindingを変更せず、本人が変わったprivate scopeだけを再生成する。
watch(() => authStore.user?.id ?? null, () => { generation.value += 1 }, { flush: 'sync' })
const ownerKey = computed(() => `${authStore.user?.id ?? 'anonymous'}:${generation.value}`)
</script>
<template>
 <RanchWidgetContent :key="ownerKey" :active="props.active" :collapsed="props.collapsed" @update:collapsed="emit('update:collapsed', $event)" />
</template>
