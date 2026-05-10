<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(loadPermissions)
</script>

<template>
  <TodoStatusLabelManagement scope="organization" :scope-id="orgId" :can-edit="isAdmin" />
</template>
