<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(eventId: number) {
  navigateTo(`/teams/${teamId}/events/${eventId}`)
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="イベント" />
      <Button label="イベント作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <EventList
      ref="listRef"
      scope-type="team"
      :scope-id="teamId"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @select="onSelect"
    />

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamId"
      @saved="onSaved"
    />
  </div>
</template>
