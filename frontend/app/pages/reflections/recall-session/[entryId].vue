<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const generation = ref(0)
watch(() => auth.user?.id ?? null, () => { generation.value += 1 }, { flush: 'sync' })
const entryId = computed(() => typeof route.params.entryId === 'string' ? route.params.entryId : '')
const sessionId = computed(() => typeof route.query.session === 'string' ? route.query.session : undefined)
function onSession(id: string) {
 if (sessionId.value !== id) void router.replace({ query: { ...route.query, session: id } })
}
</script>
<template>
 <div class="mx-auto max-w-2xl px-4 py-6">
  <ReflectionRecallSessionPrivate v-if="auth.user" :key="auth.user.id + ':' + generation + ':' + entryId + ':' + (sessionId ?? '')" :entry-id="entryId" :session-id="sessionId" @session="onSession" />
 </div>
</template>
