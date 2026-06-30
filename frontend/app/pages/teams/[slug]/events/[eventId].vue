<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

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
      <BackButton :to="`/teams/${teamSlug}/events`" label="イベント一覧に戻る" />
    </div>

    <EventDetail
      scope-type="team"
      :scope-id="teamSlug"
      :event-id="eventId"
      :can-edit="isAdminOrDeputy"
    />
  </div>
</template>
