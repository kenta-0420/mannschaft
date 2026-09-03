<script setup lang="ts">
import type { ShiftScheduleResponse } from '~/types/shift'

const props = defineProps<{
  teamId: string
  canManage: boolean
}>()

const emit = defineEmits<{
  select: [scheduleId: number]
  create: []
}>()

const shiftApi = useShiftApi()
const notification = useNotification()
const confirm = useConfirm()

const schedules = ref<ShiftScheduleResponse[]>([])
const loading = ref(true)

// CMP-260826-2127 / AC-15: 「どのシフト表を出すか」はサーバーが決める。
// かつてここで非管理者に PUBLISHED のみを出していたが、BE 側で未公開シフト表を
// 返さないようにしたため、FE 側の絞り込みは冗長であり規則の二重化になる。
// （ステータスバッジ statusConfig は表示であって判定ではないので残す。）
const visibleSchedules = computed(() => schedules.value)

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
    const data = await shiftApi.listSchedules(props.teamId)
    schedules.value = data
  } catch {
    // 取得失敗時は空表示にフォールバック
    schedules.value = []
  } finally {
    loading.value = false
  }
}

async function publish(id: number) {
  confirm.require({
    message: 'このシフトを公開しますか？メンバーに通知されます。',
    header: 'シフト公開の確認',
    icon: 'pi pi-send',
    acceptLabel: '公開する',
    rejectLabel: 'キャンセル',
    accept: async () => {
      await shiftApi.transitionStatus(id, 'PUBLISHED')
      notification.success('シフトを公開しました')
      await load()
    },
  })
}

async function archive(id: number) {
  await shiftApi.transitionStatus(id, 'ARCHIVED')
  notification.success('アーカイブしました')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">シフト表</h3>
      <Button
        v-if="canManage"
        label="新規作成"
        icon="pi pi-plus"
        size="small"
        @click="emit('create')"
      />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="4rem" class="mb-2" /></div>
    <div v-else-if="visibleSchedules.length > 0" class="space-y-2">
      <div
        v-for="s in visibleSchedules"
        :key="s.id"
        class="cursor-pointer rounded-lg border border-surface-300 p-4 transition-shadow hover:shadow-md dark:border-surface-600"
        @click="emit('select', s.id)"
      >
        <div class="flex items-center justify-between">
          <div>
            <p class="font-medium">{{ s.title }}</p>
            <p class="text-xs text-surface-500">{{ s.startDate }} 〜 {{ s.endDate }}</p>
          </div>
          <div class="flex items-center gap-2">
            <Tag
              :value="statusConfig[s.status]?.label ?? s.status"
              :severity="statusConfig[s.status]?.severity ?? 'secondary'"
              rounded
            />
            <div v-if="canManage" class="flex gap-1" @click.stop>
              <Button
                v-if="s.status === 'ADJUSTING'"
                v-tooltip="'公開'"
                icon="pi pi-send"
                text
                rounded
                size="small"
                @click="publish(s.id)"
              />
              <Button
                v-if="s.status === 'PUBLISHED'"
                v-tooltip="'アーカイブ'"
                icon="pi pi-box"
                text
                rounded
                size="small"
                @click="archive(s.id)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-table"
      message="シフト表はまだありません"
    />
  </div>
</template>
