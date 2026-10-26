<script setup lang="ts">
import type { DwellingUnit } from '~/types/resident'

definePageMeta({ layout: 'organization', middleware: 'auth' })
const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const { getUnits } = useResidentApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const units = ref<DwellingUnit[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const createDialogVisible = ref(false)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await getUnits('organization', orgSlug)
    units.value = res.data ?? []
  } catch {
    units.value = []
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function onCreated() {
  await load()
}

onMounted(async () => {
  await loadPermissions()
  await load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('property.residents.title')" />
      <Button
        v-if="isAdminOrDeputy"
        :label="t('property.residents.create')"
        icon="pi pi-plus"
        data-testid="dwelling-unit-create-button"
        @click="createDialogVisible = true"
      />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <DashboardErrorState v-else-if="loadFailed" testid="dwelling-units-error-state" @retry="load" />
    <div v-else-if="units.length > 0" class="flex flex-col gap-2">
      <div
        v-for="u in units"
        :key="u.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div
          class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-sm font-bold text-primary"
        >
          {{ u.unitNumber }}
        </div>
        <div class="flex-1">
          <p class="text-sm font-medium">
            {{ u.floor == null ? u.unitNumber : `${u.floor}F - ${u.unitNumber}` }}
          </p>
          <p class="text-xs text-surface-400">
            {{ t('property.residents.residentCount', { count: u.residentCount ?? 0 }) }}
          </p>
        </div>
        <span
          class="rounded px-2 py-0.5 text-xs font-medium"
          :class="
            (u.residentCount ?? 0) > 0
              ? 'bg-green-100 text-green-700'
              : 'bg-surface-100 text-surface-500'
          "
          >{{
            (u.residentCount ?? 0) > 0
              ? t('property.residents.occupied')
              : t('property.residents.vacant')
          }}</span
        >
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-building" :message="t('property.residents.empty')" />
    <DwellingUnitCreateDialog
      v-if="isAdminOrDeputy"
      v-model:visible="createDialogVisible"
      scope-type="organization"
      :scope-id="orgSlug"
      @created="onCreated"
    />
  </div>
</template>
