<script setup lang="ts">
const props = defineProps<{
  teamId: number
  canManage: boolean
}>()

const emit = defineEmits<{
  select: [scheduleId: number]
  create: []
}>()

const shiftApi = useShiftApi()
const notification = useNotification()

interface Schedule { id: number; title: string; periodStart: string; periodEnd: string; status: string; publishedAt: string | null; createdAt: string }

const schedules = ref<Schedule[]>([])
const loading = ref(true)

const statusConfig: Record<string, { label: string; severity: string }> = {
  DRAFT: { label: '下書き', severity: 'secondary' },
  COLLECTING: { label: '希望収集中', severity: 'info' },
  ADJUSTING: { label: '調整中', severity: 'warn' },
  PUBLISHED: { label: '公開済', severity: 'success' },
  ARCHIVED: { label: 'アーカイブ', severity: 'contrast' },
}

async function load() {
  loading.value = true
  try {
    const res = await shiftApi.listShiftSchedules(props.teamId, { size: 20 })
    schedules.value = res.data as Schedule[]
  }
  catch { schedules.value = [] }
  finally { loading.value = false }
}

async function publish(id: number) {
  if (!confirm('このシフトを公開しますか？メンバーに通知されます。')) return
  await shiftApi.publishShift(props.teamId, id)
  notification.success('シフトを公開しました')
  await load()
}

async function archive(id: number) {
  await shiftApi.archiveShift(props.teamId, id)
  notification.success('アーカイブしました')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">シフト表</h3>
      <Button v-if="canManage" label="新規作成" icon="pi pi-plus" size="small" @click="emit('create')" />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="4rem" class="mb-2" /></div>
    <div v-else-if="schedules.length > 0" class="space-y-2">
      <div
        v-for="s in schedules"
        :key="s.id"
        class="cursor-pointer rounded-lg border border-surface-200 p-4 transition-shadow hover:shadow-md dark:border-surface-700"
        @click="emit('select', s.id)"
      >
        <div class="flex items-center justify-between">
          <div>
            <p class="font-medium">{{ s.title }}</p>
            <p class="text-xs text-surface-500">{{ s.periodStart }} 〜 {{ s.periodEnd }}</p>
          </div>
          <div class="flex items-center gap-2">
            <Tag :value="statusConfig[s.status]?.label ?? s.status" :severity="statusConfig[s.status]?.severity ?? 'secondary'" rounded />
            <div v-if="canManage" class="flex gap-1" @click.stop>
              <Button v-if="s.status === 'ADJUSTING'" v-tooltip="'公開'" icon="pi pi-send" text rounded size="small" @click="publish(s.id)" />
              <Button v-if="s.status === 'PUBLISHED'" v-tooltip="'アーカイブ'" icon="pi pi-box" text rounded size="small" @click="archive(s.id)" />
            </div>
          </div>
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-table" message="シフト表はまだありません" />
  </div>
</template>
