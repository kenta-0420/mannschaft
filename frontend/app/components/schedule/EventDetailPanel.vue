<script setup lang="ts">
const props = defineProps<{
  event: {
    id: number
    title: string
    description: string | null
    location: string | null
    startAt: string
    endAt: string
    allDay: boolean
    status: string
    categoryName: string | null
    categoryColor: string | null
    createdBy: { displayName: string }
    attendanceRequired: boolean
    myAttendance: string | null
    attendanceStats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  }
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
  scopeName?: string | null
  scopeIconUrl?: string | null
}>()

const emit = defineEmits<{
  edit: []
  delete: []
  responded: []
}>()

const { formatDate, formatDateTime: isoFormatDateTime } = useDatetime()

function formatDateTime(dateStr: string, allDay: boolean): string {
  if (allDay) return formatDate(dateStr)
  return isoFormatDateTime(dateStr)
}

const statusConfig: Record<string, { label: string; severity: string }> = {
  DRAFT: { label: '下書き', severity: 'secondary' },
  PUBLISHED: { label: '公開中', severity: 'success' },
  CANCELLED: { label: 'キャンセル', severity: 'danger' },
}

// F03.10 第四陣 Wave2-B 代理出席件数バッジ
const { fetchDelegations } = useEventDelegationApi()
const delegationCount = ref(0)

onMounted(async () => {
  if (props.canEdit) {
    try {
      const res = await fetchDelegations(props.event.id, 1, 1)
      delegationCount.value = res.total ?? 0
    } catch {
      // サイドパネルの補助情報なので失敗しても 0 件扱いで継続
      delegationCount.value = 0
    }
  }
})
</script>

<template>
  <div class="space-y-4">
    <!-- ヘッダー -->
    <div class="flex items-start justify-between">
      <div>
        <div class="flex items-center gap-2">
          <h2 class="text-xl font-bold">{{ event.title }}</h2>
          <Tag
            v-if="event.categoryName"
            :value="event.categoryName"
            :style="{ backgroundColor: (event.categoryColor ?? '#6366f1') + '20', color: event.categoryColor ?? '#6366f1' }"
            rounded
          />
        </div>
        <Tag
          :value="statusConfig[event.status]?.label ?? event.status"
          :severity="statusConfig[event.status]?.severity ?? 'secondary'"
          class="mt-1"
          rounded
        />
      </div>
      <div v-if="canEdit" class="flex gap-1">
        <Button icon="pi pi-pencil" text rounded size="small" @click="emit('edit')" />
        <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="emit('delete')" />
      </div>
    </div>

    <!-- 日時・場所 -->
    <div class="space-y-2 text-sm">
      <div class="flex items-center gap-2">
        <i class="pi pi-calendar text-surface-400" />
        <span>{{ formatDateTime(event.startAt, event.allDay) }} 〜 {{ formatDateTime(event.endAt, event.allDay) }}</span>
      </div>
      <div v-if="event.location" class="flex items-center gap-2">
        <i class="pi pi-map-marker text-surface-400" />
        <span>{{ event.location }}</span>
      </div>
      <div v-if="scopeName" class="flex items-center gap-2">
        <div class="w-5 h-5 rounded-full overflow-hidden flex items-center justify-center bg-surface-200 text-surface-600 text-xs font-bold flex-shrink-0 dark:bg-surface-600 dark:text-surface-200">
          <img v-if="scopeIconUrl" :src="scopeIconUrl" class="w-full h-full object-cover" alt="">
          <span v-else>{{ scopeName.charAt(0) }}</span>
        </div>
        <span>{{ scopeName }}</span>
      </div>
      <div v-if="event.createdBy" class="flex items-center gap-2">
        <i class="pi pi-user text-surface-400" />
        <span>作成: {{ event.createdBy.displayName }}</span>
      </div>
    </div>

    <!-- 説明 -->
    <div v-if="event.description" class="rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50">
      <p class="whitespace-pre-wrap text-sm">{{ event.description }}</p>
    </div>

    <!-- 出欠パネル -->
    <AttendancePanel
      v-if="event.attendanceRequired"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :schedule-id="event.id"
      :my-attendance="event.myAttendance"
      :stats="event.attendanceStats"
      @responded="emit('responded')"
    />

    <!-- F03.10 第四陣 Wave2-B 代理出席件数バッジ（管理者 + 1件以上の場合のみ表示） -->
    <div
      v-if="canEdit && delegationCount > 0"
      class="mt-3 rounded border border-yellow-200 bg-yellow-50 p-2 text-sm dark:border-yellow-800 dark:bg-yellow-900/20"
    >
      <span class="font-medium text-yellow-800 dark:text-yellow-200">{{ $t('proxy.delegation.admin.tab') }}: </span>
      <span class="text-yellow-700 dark:text-yellow-300">{{ delegationCount }}{{ $t('proxy.delegation.admin.count_suffix') }}</span>
    </div>
  </div>
</template>
