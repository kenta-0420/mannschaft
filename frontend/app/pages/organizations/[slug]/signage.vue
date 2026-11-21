<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: ['auth', 'org-role-guard'] })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug ?? ''))
const { scopeId, resolving, failed, retry } = useSignageScopeId('ORGANIZATION', orgSlug)
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <DashboardErrorState v-if="failed" @retry="retry" />
    <PageLoading v-else-if="resolving" />
    <SignageManager v-else-if="scopeId !== null" :key="scopeId" scope-type="ORGANIZATION" :scope-id="scopeId" />
  </div>
</template>
