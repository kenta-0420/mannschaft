<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, loadPermissions } = useRoleAccess('team', teamSlug)

onMounted(loadPermissions)
</script>

<template>
  <TodoStatusLabelManagement scope="team" :scope-id="teamSlug" :can-edit="isAdmin" />
</template>
