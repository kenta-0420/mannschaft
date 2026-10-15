<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(eventId: number) {
  navigateTo(`/organizations/${orgSlug}/events/${eventId}`)
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
      scope-type="organization"
      :scope-id="orgSlug"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @select="onSelect"
    />

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgSlug"
      @saved="onSaved"
    />
  </div>
</template>
