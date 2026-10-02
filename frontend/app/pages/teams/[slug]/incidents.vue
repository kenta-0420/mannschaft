<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const teamApi = useTeamApi()

const scopeId = ref<string | null>(null)
const scopeLoading = ref(true)
const scopeLoadFailed = ref(false)
const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const showCategoryManager = ref(false)
const editId = ref<number | undefined>(undefined)
const listRef = ref<{ refresh: () => void } | null>(null)

const selectedIncidentId = computed(() => parseIncidentId(route.query.incidentId))

function parseIncidentId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  const incidentId = Number(value)
  return Number.isSafeInteger(incidentId) ? incidentId : null
}

async function resolveScopeId() {
  scopeLoading.value = true
  scopeLoadFailed.value = false
  try {
    const response = await teamApi.getTeam(teamSlug)
    const numericId = Number(response.data.numericId)
    if (!Number.isSafeInteger(numericId) || numericId <= 0) throw new Error('team_numeric_id_missing')
    scopeId.value = String(numericId)
  } catch {
    scopeLoadFailed.value = true
  } finally {
    scopeLoading.value = false
  }
}

function onSaved() {
  listRef.value?.refresh()
}

async function onSelect(incidentId: number) {
  await router.push({ path: route.path, query: { ...route.query, incidentId: String(incidentId) } })
}

async function onBack() {
  const query = { ...route.query }
  delete query.incidentId
  await router.replace({ path: route.path, query })
  listRef.value?.refresh()
}

onMounted(async () => {
  await Promise.all([loadPermissions(), resolveScopeId()])
})
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="インシデント管理" />
    </div>

    <div v-if="scopeLoading" class="flex justify-center py-12">
      <LoadingBounce />
    </div>

    <div v-else-if="scopeLoadFailed" class="py-12 text-center text-surface-500">
      <p class="mb-4">チーム情報の取得に失敗しました。</p>
      <Button label="再試行" @click="resolveScopeId" />
    </div>

    <template v-else-if="scopeId">
      <div v-if="selectedIncidentId" class="mx-auto max-w-3xl">
        <IncidentDetail
          :incident-id="selectedIncidentId"
          :can-manage="isAdminOrDeputy"
          @back="onBack"
          @updated="onSaved"
        />
      </div>

      <div v-else>
        <IncidentList
          ref="listRef"
          scope-type="TEAM"
          :scope-id="scopeId"
          :can-manage="isAdminOrDeputy"
          @select="onSelect($event.id)"
          @create="showCreateDialog = true"
          @manage-categories="showCategoryManager = true"
        />
      </div>

      <IncidentForm
        v-model:visible="showCreateDialog"
        scope-type="TEAM"
        :scope-id="scopeId"
        @saved="onSaved"
      />

      <IncidentForm
        v-model:visible="showEditDialog"
        scope-type="TEAM"
        :scope-id="scopeId"
        :edit-id="editId"
        @saved="onSaved"
      />

      <IncidentCategoryManager
        v-model:visible="showCategoryManager"
        scope-type="TEAM"
        :scope-id="scopeId"
      />
    </template>
  </div>
</template>
