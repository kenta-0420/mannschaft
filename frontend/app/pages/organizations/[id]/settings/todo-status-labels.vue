<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgId = String(route.params.id)
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(loadPermissions)
</script>

<template>
  <TodoStatusLabelManagement scope="organization" :scope-id="orgId" :can-edit="isAdmin" />
</template>
