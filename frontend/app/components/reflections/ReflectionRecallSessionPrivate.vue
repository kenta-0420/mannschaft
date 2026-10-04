<script setup lang="ts">
const props = defineProps<{ entryId: string; sessionId?: string }>()
const emit = defineEmits<{ session: [id: string] }>()
const api = useRecallSessionApi(useRanchCommand('reflection-recall'))
</script>
<template>
 <ReflectionRecallSessionContent :entry-id="props.entryId" :session-id="props.sessionId" :api="api" @session="emit('session', $event)">
  <template #original="{ entry }"><ReflectionRecallOriginal :entry="entry" /></template>
 </ReflectionRecallSessionContent>
</template>
