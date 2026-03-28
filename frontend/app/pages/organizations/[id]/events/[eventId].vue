<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const eventId = Number(route.params.eventId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <NuxtLink :to="`/organizations/${orgId}/events`" class="text-sm text-primary hover:underline">
        <i class="pi pi-arrow-left mr-1" />イベント一覧に戻る
      </NuxtLink>
    </div>

    <EventDetail
      scope-type="organization"
      :scope-id="orgId"
      :event-id="eventId"
      :can-edit="isAdminOrDeputy"
    />
  </div>
</template>
