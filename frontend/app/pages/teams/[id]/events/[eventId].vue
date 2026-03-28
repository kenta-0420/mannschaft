<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <NuxtLink :to="`/teams/${teamId}/events`" class="text-sm text-primary hover:underline">
        <i class="pi pi-arrow-left mr-1" />イベント一覧に戻る
      </NuxtLink>
    </div>

    <EventDetail
      scope-type="team"
      :scope-id="teamId"
      :event-id="eventId"
      :can-edit="isAdminOrDeputy"
    />
  </div>
</template>
