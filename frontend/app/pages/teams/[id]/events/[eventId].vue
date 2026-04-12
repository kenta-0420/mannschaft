<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const loading = ref(true)

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4">
      <BackButton :to="`/teams/${teamId}/events`" label="イベント一覧に戻る" />
    </div>

    <EventDetail
      scope-type="team"
      :scope-id="teamId"
      :event-id="eventId"
      :can-edit="isAdminOrDeputy"
    />
  </div>
</template>
