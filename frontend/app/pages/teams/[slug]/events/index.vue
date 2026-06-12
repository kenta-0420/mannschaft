<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(eventId: number) {
  navigateTo(`/teams/${teamSlug}/events/${eventId}`)
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
      :scope-id="teamSlug"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @select="onSelect"
    />

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamSlug"
      @saved="onSaved"
    />
  </div>
</template>
