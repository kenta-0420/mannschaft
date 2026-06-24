<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgSlug)

onMounted(loadPermissions)
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <TodoStatusLabelManagement scope="organization" :scope-id="orgSlug" :can-edit="isAdmin" />
  </div>
</template>
