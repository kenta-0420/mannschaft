<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = String(route.params.id)
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

onMounted(loadPermissions)
</script>

<template>
  <TodoStatusLabelManagement scope="team" :scope-id="teamId" :can-edit="isAdmin" />
</template>
