<script setup lang="ts">
import type { ReflectionEntryResponse } from '~/types/reflection'
import { useReflectionStructuredContent, type JsonNode } from '~/composables/useReflectionStructuredContent'
const props = defineProps<{ entry: ReflectionEntryResponse }>()
const { toStructured } = useReflectionStructuredContent()
const structured = computed(() => props.entry.structuredContent ? toStructured(props.entry.structuredContent as unknown as JsonNode) : null)
</script>
<template><ReflectionStructuredView v-if="structured" :content="structured" /></template>
