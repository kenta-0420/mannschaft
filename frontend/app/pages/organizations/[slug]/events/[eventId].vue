<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

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
      <BackButton :to="`/organizations/${orgSlug}/events`" label="イベント一覧に戻る" />
    </div>

    <EventDetail
      scope-type="organization"
      :scope-id="orgSlug"
      :event-id="eventId"
      :can-edit="isAdminOrDeputy"
    />
  </div>
</template>
