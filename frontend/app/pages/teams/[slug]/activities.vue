<script setup lang="ts">
import type { ActivityRecordResponse } from '~/types/activity'
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('team', teamSlug)

const { getActivities } = useActivityApi()
const { showError } = useNotification()

const activities = ref<ActivityRecordResponse[]>([])
const loading = ref(false)
const showCreate = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getActivities({ scope_type: 'TEAM', scope_id: teamSlug })
    activities.value = res.data
  } catch {
    showError(t('activity.loadError'))
  } finally {
    loading.value = false
  }
}

/**
 * 公開 URL を生成する（SNS シェア用）
 */
function getPublicUrl(activityId: number): string {
  if (typeof window !== 'undefined') {
    return `${window.location.origin}/activity/${activityId}`
  }
  return `/activity/${activityId}`
}

onMounted(async () => {
  await loadPermissions()
  load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <PageHeader :title="$t('activity.pageTitle')" />
      </div>
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
      <SectionCard v-for="act in activities" :key="act.id">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ act.title }}</h3>
          <span class="text-xs text-surface-400">{{ act.activityDate }}</span>
        </div>
        <p v-if="act.location" class="mt-1 text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ act.location }}
        </p>
        <p v-if="act.description" class="mt-1 text-sm text-surface-600">{{ act.description }}</p>
        <div class="mt-2 flex items-center justify-between">
          <div class="text-xs text-surface-400">
            {{ $t('activity.participantCount', { count: act.participantCount }) }}
          </div>
          <!-- PUBLIC の活動記録のみシェアボタンを表示 -->
          <Button
            v-if="act.isPublic"
            :label="$t('share.title')"
            icon="pi pi-share-alt"
            severity="secondary"
            outlined
            size="small"
            @click="() => navigateTo(getPublicUrl(act.id))"
          />
        </div>
      </SectionCard>
      <DashboardEmptyState
        v-if="activities.length === 0"
        icon="pi pi-history"
        :message="$t('activity.noRecords')"
      />
    </div>

    <ActivityCreateDialog
      v-model:visible="showCreate"
      scope-type="TEAM"
      :scope-id="teamSlug"
      @created="load"
    />
  </div>
</template>
