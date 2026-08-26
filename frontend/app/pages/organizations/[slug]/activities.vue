<script setup lang="ts">
import type { ActivityRecordResponse } from '~/types/activity'
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const orgSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('organization', orgSlug)

const { getActivities, publishActivity } = useActivityApi()
const { error: showError, success: showSuccess } = useNotification()

const activities = ref<ActivityRecordResponse[]>([])
const loading = ref(false)
const showCreate = ref(false)
const showGuide = ref(false)
const statusFilter = ref<string | undefined>(undefined)
const publishingId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { scope_type: 'ORGANIZATION', scope_id: orgSlug }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await getActivities(params)
    activities.value = res.data
  } catch {
    showError(t('activity.loadError'))
  } finally {
    loading.value = false
  }
}

/** DRAFT の活動記録を公開する */
async function handlePublish(activityId: number) {
  publishingId.value = activityId
  try {
    await publishActivity(activityId)
    showSuccess(t('activity.publish.success'))
    await load()
  } catch {
    showError(t('activity.publish.error'))
  } finally {
    publishingId.value = null
  }
}

function getStatusClass(status: string | undefined): string {
  switch (status) {
    case 'DRAFT': return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-200'
    case 'PUBLISHED': return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-200'
    default: return 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-200'
  }
}

const statusFilterOptions = computed(() => [
  { label: t('activity.list.filterAll'), value: undefined },
  { label: t('activity.statusLabel.DRAFT'), value: 'DRAFT' },
  { label: t('activity.statusLabel.PUBLISHED'), value: 'PUBLISHED' },
])

watch(statusFilter, () => load())

onMounted(async () => {
  await loadPermissions()
  load()
})
</script>

<template>
  <div>
    <PageHeader :title="$t('activity.pageTitle')" help @help="showGuide = true" />

    <div class="mb-4 mt-3 flex items-center justify-between">
      <Select
        v-model="statusFilter"
        :options="statusFilterOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('activity.list.filterAll')"
        class="w-36"
        data-testid="activity-status-filter"
      />
      <Button
        v-if="isMember"
        :label="$t('activity.addRecord')"
        icon="pi pi-plus"
        data-testid="activity-add-record"
        @click="showCreate = true"
      />
    </div>

    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="act in activities"
        :key="act.id"
        :class="{ 'border-amber-200 bg-amber-50/40 dark:border-amber-700/40 dark:bg-amber-900/10': act.status === 'DRAFT' }"
      >
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <!-- DRAFTバッジ -->
            <span
              v-if="act.status"
              :class="getStatusClass(act.status)"
              class="rounded px-2 py-0.5 text-xs font-medium"
              :data-testid="`activity-status-${act.id}`"
            >
              {{ $t(`activity.statusLabel.${act.status}`) }}
            </span>
            <h3 class="text-sm font-semibold">{{ act.title }}</h3>
          </div>
          <span class="text-xs text-surface-400">{{ act.activityDate }}</span>
        </div>
        <p v-if="act.location" class="mt-1 text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ act.location }}
        </p>
        <p v-if="act.description" class="mt-1 text-sm text-surface-600">{{ act.description }}</p>
        <div class="mt-2 flex items-center justify-between">
          <div class="text-xs text-surface-400">{{ $t('activity.participantCount', { count: act.participantCount }) }}</div>
          <!-- DRAFT: 公開ボタン -->
          <Button
            v-if="act.status === 'DRAFT' && isMember"
            :label="$t('activity.list.editDraft')"
            icon="pi pi-send"
            severity="warn"
            outlined
            size="small"
            :loading="publishingId === act.id"
            :data-testid="`activity-publish-${act.id}`"
            @click="handlePublish(act.id)"
          />
        </div>
      </SectionCard>
      <DashboardEmptyState v-if="activities.length === 0" icon="pi pi-history" :message="$t('activity.noRecords')" />
    </div>

    <ActivityCreateDialog
      v-model:visible="showCreate"
      scope-type="ORGANIZATION"
      :scope-id="orgSlug"
      @created="load"
    />

    <ActivityGuideModal v-model:visible="showGuide" />
  </div>
</template>
