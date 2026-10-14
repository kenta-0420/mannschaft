<script setup lang="ts">
import type { ArchiveTeamSummary } from '~/composables/useTeamArchiveAction'
const api = useApi()
const { handleApiError } = useErrorHandler()
const expanded = ref(false)
const teams = ref<ArchiveTeamSummary[]>([])
const loading = ref(false)
const error = ref(false)
let generation = 0
onScopeDispose(() => { generation++ })

async function load() {
  const current = ++generation
  loading.value = true
  error.value = false
  try {
    const response = await api<{ data: ArchiveTeamSummary[] }>('/api/v1/me/teams', {
      query: { includeArchived: true },
    })
    if (current === generation) teams.value = response.data.filter(team => team.isArchived)
  }
  catch (cause) {
    if (current === generation) {
      error.value = true
      handleApiError(cause, 'アーカイブ済みチーム一覧取得')
    }
    throw cause
  }
  finally {
    if (current === generation) loading.value = false
  }
}
async function refresh() {
  await load()
}
async function toggle() {
  expanded.value = !expanded.value
  if (expanded.value) {
    try { await load() }
    catch { /* load でエラー表示・共通通知済み。 */ }
  }
}
async function retry() {
  try { await load() }
  catch { /* load でエラー表示・共通通知済み。 */ }
}
</script>

<template>
  <SectionCard :title="$t('teamShell.archive.listTitle')" class="mt-6">
    <Button
      :label="expanded ? $t('teamShell.archive.hideList') : $t('teamShell.archive.showList')"
      severity="secondary"
      outlined
      class="min-h-11"
      :aria-expanded="expanded"
      data-testid="archived-teams-toggle"
      @click="toggle"
    />
    <template v-if="expanded">
      <PageLoading v-if="loading" />
      <DashboardErrorState v-else-if="error" :message="$t('teamShell.archive.fetchError')" @retry="retry" />
      <DashboardEmptyState v-else-if="teams.length === 0" icon="pi pi-box" :message="$t('teamShell.archive.empty')" />
      <ul v-else class="mt-4 space-y-3">
        <li v-for="team in teams" :key="team.id" class="flex flex-wrap items-center justify-between gap-2">
          <span class="min-w-0 break-words">{{ team.name }}</span>
          <TeamUnarchiveButton :team="team" :refresh="refresh" />
        </li>
      </ul>
    </template>
  </SectionCard>
</template>
