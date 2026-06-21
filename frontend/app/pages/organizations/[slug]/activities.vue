<script setup lang="ts">
import type { ActivityRecordResponse } from '~/types/activity'
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const orgSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('organization', orgSlug)

const { getActivities } = useActivityApi()
const { showError } = useNotification()

const activities = ref<ActivityRecordResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getActivities({ scope_type: 'ORGANIZATION', scope_id: orgSlug })
    activities.value = res.data
  } catch {
    showError(t('activity.loadError'))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadPermissions()
  load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('activity.pageTitle')" />
      <Button v-if="isMember" :label="$t('activity.addRecord')" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="act in activities"
        :key="act.id"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ act.title }}</h3>
          <span class="text-xs text-surface-400">{{ act.activityDate }}</span>
        </div>
        <p v-if="act.location" class="mt-1 text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ act.location }}
        </p>
        <p v-if="act.description" class="mt-1 text-sm text-surface-600">{{ act.description }}</p>
        <div class="mt-2 text-xs text-surface-400">{{ $t('activity.participantCount', { count: act.participantCount }) }}</div>
      </SectionCard>
      <DashboardEmptyState v-if="activities.length === 0" icon="pi pi-history" :message="$t('activity.noRecords')" />
    </div>
  </div>
</template>
