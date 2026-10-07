<script setup lang="ts">
import type { ActivityRecordResponse } from '~/types/activity'
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  scheduleId: number
}>()
const { t } = useI18n()
const api = useApi()
const { resolveScopeId } = useActivityScopeId()
const { handleApiError } = useErrorHandler()
const { isMember, loadPermissions } = useRoleAccess(props.scopeType, props.scopeId)
const records = ref<ActivityRecordResponse[]>([])
const loading = ref(true)
const error = shallowRef<unknown>(null)
const creating = ref(false)
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await loadPermissions()
    const path = `/api/v1/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/schedules/${props.scheduleId}/activities`
    records.value = (await api<{ data: ActivityRecordResponse[] }>(path)).data
  } catch (loadError) {
    error.value = loadError
    handleApiError(loadError, '予定の活動記録一覧')
  } finally {
    loading.value = false
  }
}
async function createDraft(): Promise<void> {
  if (!isMember.value || creating.value) return
  creating.value = true
  try {
    const scopeType = props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION'
    const scopeId = await resolveScopeId(scopeType, props.scopeId)
    if (scopeId === null) throw new Error('活動記録のスコープを解決できません')
    const result = await api<{ data: ActivityRecordResponse }>(
      `/api/v1/activities/draft-from-schedule?scope_type=${scopeType}&scope_id=${scopeId}`,
      { method: 'POST', body: { scheduleId: props.scheduleId } },
    )
    await navigateTo(`/activities/${result.data.id}`)
  } catch (createError) {
    handleApiError(createError, '予定から活動記録を作成')
  } finally {
    creating.value = false
  }
}
onMounted(load)
watch(() => [props.scopeType, props.scopeId, props.scheduleId], load)
</script>

<template>
  <div class="space-y-2 border-t border-surface-200 pt-3 dark:border-surface-700">
    <h3 class="font-medium">{{ t('activity.schedule.records') }}</h3>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <ul v-else class="space-y-2">
      <li v-for="record in records" :key="record.id">
        <NuxtLink
          :to="`/activities/${record.id}`"
          class="inline-flex min-h-11 items-center underline"
          :data-testid="`schedule-activity-${record.id}`"
          >{{ record.title }}</NuxtLink
        >
      </li>
    </ul>
    <Button
      v-if="isMember"
      :label="t('activity.schedule.create')"
      icon="pi pi-file-edit"
      outlined
      class="min-h-11 min-w-11 w-full"
      :loading="creating"
      :disabled="loading || !!error"
      data-testid="schedule-create-activity"
      @click="createDraft"
    />
  </div>
</template>
