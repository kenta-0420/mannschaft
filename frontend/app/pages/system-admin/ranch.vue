<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const auth = useAuthStore()
const commandState = useNuxtApp().$ranchCommandMemory.scopes['ranch-admin']
let generation = 0
const accountKey = ref('')
watch([() => auth.user?.id ?? null, () => auth.isSystemAdmin], ([id, isSystemAdmin]) => {
  generation += 1
  accountKey.value = id !== null && isSystemAdmin ? `${id}:${generation}` : ''
  if (!isSystemAdmin) {
    // 同じ本人の降格でも運営命令を持ち越さない。abort前に旧runの参照を無効化する。
    const oldRun = commandState.activeRun
    commandState.pending.value = null
    commandState.running.value = false
    commandState.activeRun = null
    // abortは到達済みのサーバーcommitを取り消す保証ではない。
    oldRun?.controller.abort()
  }
}, { immediate: true, flush: 'sync' })
</script>

<template>
  <RanchAdminContent v-if="accountKey" :key="accountKey" />
  <DashboardErrorState v-else kind="forbidden" />
</template>
