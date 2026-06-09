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
  <TodoStatusLabelManagement scope="organization" :scope-id="orgSlug" :can-edit="isAdmin" />
</template>
