<script setup lang="ts">
const { getActivity } = useDashboardApi()

interface Activity {
  id: number
  activityType: string
  actorName: string
  actorAvatarUrl: string | null
  targetType: string
  targetId: number
  targetTitle: string
  scopeName: string
  createdAt: string
}

const activities = ref<Activity[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getActivity({ limit: 8 })
    activities.value = res.data
  }
  catch { activities.value = [] }
  finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard title="最近のアクティビティ" icon="pi pi-history" :loading="loading" :col-span="2" refreshable @refresh="load">
    <div v-if="activities.length > 0" class="divide-y divide-surface-100 dark:divide-surface-700">
      <ActivityItem
        v-for="activity in activities"
        :key="activity.id"
        :activity-type="activity.activityType"
        :actor-name="activity.actorName"
        :actor-avatar-url="activity.actorAvatarUrl"
        :target-title="activity.targetTitle"
        :scope-name="activity.scopeName"
        :created-at="activity.createdAt"
      />
    </div>
    <DashboardEmptyState v-else icon="pi pi-history" message="まだアクティビティはありません" />
  </DashboardWidgetCard>
</template>
