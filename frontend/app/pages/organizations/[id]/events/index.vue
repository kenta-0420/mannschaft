<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(eventId: number) {
  navigateTo(`/organizations/${orgId}/events/${eventId}`)
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">イベント</h1>
      <Button label="イベント作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <EventList
      ref="listRef"
      scope-type="organization"
      :scope-id="orgId"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @select="onSelect"
    />

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgId"
      @saved="onSaved"
    />
  </div>
</template>
