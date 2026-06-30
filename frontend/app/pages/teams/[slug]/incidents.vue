<script setup lang="ts">
import type { IncidentSummaryResponse } from '~/types/incident'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const selectedIncident = ref<IncidentSummaryResponse | null>(null)
const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const showCategoryManager = ref(false)
const editId = ref<number | undefined>(undefined)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

function onSelect(incident: IncidentSummaryResponse) {
  selectedIncident.value = incident
}

function onBack() {
  selectedIncident.value = null
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="インシデント管理" />
    </div>

    <div v-if="selectedIncident" class="mx-auto max-w-3xl">
      <IncidentDetail
        :incident-id="selectedIncident.id"
        :can-manage="isAdminOrDeputy"
        @back="onBack"
        @updated="onSaved"
      />
    </div>

    <div v-else>
      <IncidentList
        ref="listRef"
        scope-type="TEAM"
        :scope-id="teamSlug"
        :can-manage="isAdminOrDeputy"
        @select="onSelect"
        @create="showCreateDialog = true"
        @manage-categories="showCategoryManager = true"
      />
    </div>

    <IncidentForm
      v-model:visible="showCreateDialog"
      scope-type="TEAM"
      :scope-id="teamSlug"
      @saved="onSaved"
    />

    <IncidentForm
      v-model:visible="showEditDialog"
      scope-type="TEAM"
      :scope-id="teamSlug"
      :edit-id="editId"
      @saved="onSaved"
    />

    <IncidentCategoryManager
      v-model:visible="showCategoryManager"
      scope-type="TEAM"
      :scope-id="teamSlug"
    />
  </div>
</template>
