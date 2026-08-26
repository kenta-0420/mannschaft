<script setup lang="ts">
import { useAttendanceResponder } from '~/composables/schedule/useAttendanceResponder'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  scheduleId: number
  myAttendance: string | null
  stats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
}>()

const emit = defineEmits<{
  responded: []
}>()

// 出欠回答の呼び出し/トーストは共通 composable に集約（モバイル行内 RSVP と共有）。
const { responding, respond: respondAttendance } = useAttendanceResponder(
  props.scopeType,
  props.scopeId,
)
const comment = ref('')
const showCommentInput = ref(false)

async function respond(status: string) {
  const ok = await respondAttendance(props.scheduleId, status, comment.value)
  if (ok) {
    comment.value = ''
    showCommentInput.value = false
    emit('responded')
  }
}

// status は BE 正準（ATTENDING/ABSENT/UNDECIDED）と完全一致させる。ラベルは不変。
const attendanceButtons = [
  { status: 'ATTENDING', label: '出席', icon: 'pi pi-check', severity: 'success' as const },
  { status: 'ABSENT', label: '欠席', icon: 'pi pi-times', severity: 'danger' as const },
  { status: 'UNDECIDED', label: '未定', icon: 'pi pi-question', severity: 'warn' as const },
]
</script>

<template>
  <SectionCard>
    <h3 class="mb-3 text-sm font-semibold">出欠回答</h3>

    <!-- 回答ボタン -->
    <div class="mb-3 flex gap-2">
      <Button
        v-for="btn in attendanceButtons"
        :key="btn.status"
        :label="btn.label"
        :icon="btn.icon"
        :severity="btn.severity"
        :outlined="myAttendance !== btn.status"
        size="small"
        :loading="responding"
        @click="respond(btn.status)"
      />
      <Button
        v-if="!showCommentInput"
        v-tooltip="'コメント付きで回答'"
        icon="pi pi-comment"
        text
        rounded
        size="small"
        @click="showCommentInput = true"
      />
    </div>

    <!-- コメント入力 -->
    <div v-if="showCommentInput" class="mb-3">
      <InputText v-model="comment" class="w-full" placeholder="コメント（任意）" />
    </div>

    <!-- 統計 -->
    <div v-if="stats" class="flex gap-4 text-sm">
      <span class="text-green-600"><i class="pi pi-check mr-1" />{{ stats.yes }}</span>
      <span class="text-red-600"><i class="pi pi-times mr-1" />{{ stats.no }}</span>
      <span class="text-yellow-600"><i class="pi pi-question mr-1" />{{ stats.maybe }}</span>
      <span class="text-surface-400"><i class="pi pi-clock mr-1" />{{ stats.pending }}</span>
      <span class="text-surface-500">/ {{ stats.total }}名</span>
    </div>
  </SectionCard>
</template>
