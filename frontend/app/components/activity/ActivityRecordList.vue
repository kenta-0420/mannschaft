<script setup lang="ts">
import type { ActivityRecordResponse } from '~/types/activity'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  isMember: boolean
}>()
const { t } = useI18n()
const { getActivities } = useActivityApi()
const { resolveScopeId } = useActivityScopeId()
const { handleApiError } = useErrorHandler()
const activities = ref<ActivityRecordResponse[]>([])
const loading = ref(false)
const loadError = shallowRef<unknown>(null)
const showCreate = ref(false)
const statusFilter = ref<string | undefined>()
const statusOptions = computed(() => [
  { label: t('activity.list.filterAll'), value: undefined },
  { label: t('activity.statusLabel.DRAFT'), value: 'DRAFT' },
  { label: t('activity.statusLabel.PUBLISHED'), value: 'PUBLISHED' },
])

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const scopeId = await resolveScopeId(props.scopeType, props.scopeId)
    if (scopeId === null) throw new Error('活動記録のスコープを解決できません')
    activities.value = (
      await getActivities({
        scope_type: props.scopeType,
        scope_id: scopeId,
        status: statusFilter.value,
      })
    ).data
  } catch (error) {
    loadError.value = error
    handleApiError(error, '活動記録一覧')
  } finally {
    loading.value = false
  }
}
watch(statusFilter, load)
onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 mt-3 flex flex-wrap items-center justify-between gap-3">
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        class="w-36"
        data-testid="activity-status-filter"
      />
      <Button
        v-if="isMember"
        class="min-h-11 min-w-11"
        :label="t('activity.addRecord')"
        icon="pi pi-plus"
        data-testid="activity-add-record"
        @click="showCreate = true"
      />
    </div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="load" />
    <div v-else class="space-y-3">
      <SectionCard v-for="act in activities" :key="act.id">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex min-w-0 items-center gap-2">
            <ActivityStatusBadges :record="act" />
            <NuxtLink
              :to="`/activities/${act.id}`"
              class="inline-flex min-h-11 min-w-0 items-center break-words font-semibold hover:underline"
              :data-testid="`activity-detail-${act.id}`"
              >{{ act.title }}</NuxtLink
            >
          </div>
          <span class="text-sm text-surface-500">{{ act.activityDate }}</span>
        </div>
        <p
          v-if="!act.metadataOnly && act.description"
          class="mt-2 whitespace-pre-wrap break-words text-sm"
        >
          {{ act.description }}
        </p>
        <div class="mt-3 flex flex-wrap gap-2">
          <Button
            class="min-h-11 min-w-11"
            :label="
              t(
                !act.metadataOnly && act.status === 'DRAFT'
                  ? 'activity.list.editDraft'
                  : 'activity.detail.open',
              )
            "
            icon="pi pi-arrow-right"
            outlined
            size="small"
            @click="navigateTo(`/activities/${act.id}`)"
          />
          <Button
            v-if="!act.metadataOnly && act.visibility === 'PUBLIC' && act.status === 'PUBLISHED'"
            class="min-h-11 min-w-11"
            :label="t('share.title')"
            icon="pi pi-share-alt"
            severity="secondary"
            outlined
            size="small"
            @click="navigateTo(`/activity/${act.id}`)"
          />
        </div>
      </SectionCard>
      <DashboardEmptyState
        v-if="activities.length === 0"
        icon="pi pi-history"
        :message="t('activity.noRecords')"
      />
    </div>
    <ActivityCreateDialog
      v-model:visible="showCreate"
      :scope-type="scopeType"
      :scope-id="scopeId"
      @created="load"
    />
  </div>
</template>
