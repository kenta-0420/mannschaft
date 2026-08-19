<script setup lang="ts">
import type { ActivityDetailField } from '~/types/dashboard'

const { getActivity } = useDashboardApi()
const { captureQuiet } = useErrorReport()

interface Activity {
  id: number
  activityType: string
  actorName: string
  actorAvatarUrl: string | null
  targetType: string
  targetId: number
  targetTitle: string
  /** §3.2 detail.fields。既存7種別（detail = null）は空配列 */
  detailFields: ActivityDetailField[]
  scopeName: string
  createdAt: string
}

const activities = ref<Activity[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getActivity({ limit: 20 })
    activities.value = (res.data?.items ?? []).map((a) => ({
      id: a.id,
      activityType: a.type,
      actorName: a.actor?.displayName ?? '',
      actorAvatarUrl: a.actor?.avatarUrl ?? null,
      targetType: a.targetType,
      targetId: a.targetId,
      // §4.1 裁定: 表示用タイトルの正本は detail.title。summary は
      // ActivityType 固定文言でタイトルを差し込めないため、SCHEDULE 系では予定名が出ない。
      // detail を持たない既存7種別のみ summary にフォールバックする。
      targetTitle: a.detail?.title ?? a.summary,
      detailFields: a.detail?.fields ?? [],
      scopeName: a.scopeName,
      createdAt: a.createdAt,
    }))
  } catch (error) {
    captureQuiet(error, { context: 'WidgetRecentActivity: アクティビティ取得' })
    activities.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="最近のアクティビティ"
    icon="pi pi-history"
    :loading="loading"
    :col-span="2"
    refreshable
    @refresh="load"
  >
    <div v-if="activities.length > 0" class="divide-y divide-surface-300 dark:divide-surface-600">
      <ActivityItem
        v-for="activity in activities"
        :key="activity.id"
        :activity-type="activity.activityType"
        :actor-name="activity.actorName"
        :actor-avatar-url="activity.actorAvatarUrl"
        :target-title="activity.targetTitle"
        :detail-fields="activity.detailFields"
        :scope-name="activity.scopeName"
        :created-at="activity.createdAt"
        :target-type="activity.targetType"
        :target-id="activity.targetId"
      />
      <div class="flex justify-end pt-2">
        <NuxtLink to="/timeline" class="text-sm text-primary hover:underline">
          {{ $t('button.view_all') }}
        </NuxtLink>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-history" message="まだアクティビティはありません" />
  </DashboardWidgetCard>
</template>
