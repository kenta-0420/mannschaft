<script setup lang="ts">
import type { DwellingUnit } from '~/types/resident'

definePageMeta({ layout: 'team', middleware: 'auth' })
const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const { getUnits } = useResidentApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const units = ref<DwellingUnit[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const createDialogVisible = ref(false)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await getUnits('team', teamSlug)
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
      <SectionCard v-for="u in units" :key="u.id" class="flex items-center gap-4">
        <div
          class="flex h-10 w-10 items-center justify-center rounded-lg bg-surface-100 text-sm font-bold"
        >
          {{ u.unitNumber }}
        </div>
        <div class="flex-1">
          <p class="text-sm font-medium">{{ u.unitNumber }}</p>
          <p class="text-xs text-surface-400">
            {{
              (u.residentCount ?? 0) === 0
                ? t('property.residents.vacant')
                : t('property.residents.residentCount', { count: u.residentCount ?? 0 })
            }}
          </p>
        </div>
        <Badge
          :value="
            (u.residentCount ?? 0) === 0
              ? t('property.residents.vacant')
              : t('property.residents.occupied')
          "
          :severity="(u.residentCount ?? 0) === 0 ? 'warning' : 'success'"
        />
      </SectionCard>
    </div>
    <DashboardEmptyState v-else icon="pi pi-building" :message="t('property.residents.empty')" />
    <DwellingUnitCreateDialog
      v-if="isAdminOrDeputy"
      v-model:visible="createDialogVisible"
      scope-type="team"
      :scope-id="teamSlug"
      @created="onCreated"
    />
  </div>
</template>
