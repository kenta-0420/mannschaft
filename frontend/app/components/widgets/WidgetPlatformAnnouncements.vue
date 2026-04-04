<script setup lang="ts">
const { getPlatformAnnouncements } = useDashboardApi()

interface Announcement {
  id: number
  title: string
  content: string
  severity: 'INFO' | 'WARNING' | 'URGENT'
  isPinned: boolean
  publishedAt: string
}

const announcements = ref<Announcement[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getPlatformAnnouncements()
    announcements.value = res.data
  } catch {
    announcements.value = []
  } finally {
    loading.value = false
  }
}

const severityIcon: Record<string, string> = {
  INFO: 'pi pi-info-circle',
  WARNING: 'pi pi-exclamation-triangle',
  URGENT: 'pi pi-times-circle',
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard title="お知らせ" icon="pi pi-megaphone" :loading="loading">
    <div v-if="announcements.length > 0" class="space-y-2">
      <div
        v-for="ann in announcements"
        :key="ann.id"
        class="rounded-lg border p-3"
        :class="{
          'border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-900/20':
            ann.severity === 'INFO',
          'border-yellow-200 bg-yellow-50 dark:border-yellow-800 dark:bg-yellow-900/20':
            ann.severity === 'WARNING',
          'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20':
            ann.severity === 'URGENT',
        }"
      >
        <div class="flex items-start gap-2">
          <i :class="severityIcon[ann.severity]" class="mt-0.5" />
          <div>
            <div class="flex items-center gap-2">
              <p class="text-sm font-semibold">{{ ann.title }}</p>
              <i v-if="ann.isPinned" class="pi pi-thumbtack text-xs text-surface-400" />
            </div>
            <p class="mt-1 text-xs text-surface-600 dark:text-surface-400">{{ ann.content }}</p>
          </div>
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-megaphone" message="運営からのお知らせはありません" />
  </DashboardWidgetCard>
</template>
