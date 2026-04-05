<script setup lang="ts">
import type { IncidentSummaryResponse } from '~/types/incident'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

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
      <h1 class="text-2xl font-bold">インシデント管理</h1>
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
        scope-type="ORGANIZATION"
        :scope-id="orgId"
        :can-manage="isAdminOrDeputy"
        @select="onSelect"
        @create="showCreateDialog = true"
        @manage-categories="showCategoryManager = true"
      />
    </div>

    <IncidentForm
      v-model:visible="showCreateDialog"
      scope-type="ORGANIZATION"
      :scope-id="orgId"
      @saved="onSaved"
    />

    <IncidentForm
      v-model:visible="showEditDialog"
      scope-type="ORGANIZATION"
      :scope-id="orgId"
      :edit-id="editId"
      @saved="onSaved"
    />

    <IncidentCategoryManager
      v-model:visible="showCategoryManager"
      scope-type="ORGANIZATION"
      :scope-id="orgId"
    />
  </div>
</template>
