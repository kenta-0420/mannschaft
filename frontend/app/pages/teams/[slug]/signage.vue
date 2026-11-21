<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug ?? ''))
const { scopeId, resolving, failed, retry } = useSignageScopeId('TEAM', teamSlug)
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <DashboardErrorState v-if="failed" @retry="retry" />
    <PageLoading v-else-if="resolving" />
    <SignageManager v-else-if="scopeId !== null" :key="scopeId" scope-type="TEAM" :scope-id="scopeId" />
  </div>
</template>
