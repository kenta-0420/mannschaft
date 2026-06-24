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
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <TodoStatusLabelManagement scope="team" :scope-id="teamSlug" :can-edit="isAdmin" />
  </div>
</template>
