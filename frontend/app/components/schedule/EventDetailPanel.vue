<script setup lang="ts">
defineProps<{
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
    myAttendance: string | null
    attendanceStats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  }
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
}>()

const emit = defineEmits<{
  edit: []
  delete: []
  responded: []
}>()

function formatDateTime(dateStr: string, allDay: boolean): string {
  const d = new Date(dateStr)
  if (allDay) return d.toLocaleDateString('ja-JP', { year: 'numeric', month: 'long', day: 'numeric' })
  return d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' }) + ' ' +
    d.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' })
}

const statusConfig: Record<string, { label: string; severity: string }> = {
  DRAFT: { label: '下書き', severity: 'secondary' },
  PUBLISHED: { label: '公開中', severity: 'success' },
  CANCELLED: { label: 'キャンセル', severity: 'danger' },
}
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
      v-if="event.attendanceStats !== null"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :schedule-id="event.id"
      :my-attendance="event.myAttendance"
      :stats="event.attendanceStats"
      @responded="emit('responded')"
    />
  </div>
</template>
