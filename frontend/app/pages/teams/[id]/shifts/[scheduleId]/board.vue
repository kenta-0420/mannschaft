<template>
  <div class="flex flex-col h-screen overflow-hidden">
    <!-- ヘッダー -->
    <div class="flex items-center justify-between px-4 py-3 border-b border-surface-200 bg-white flex-shrink-0">
      <div class="flex items-center gap-3">
        <NuxtLink
          :to="`/teams/${teamId}/shifts`"
          class="text-surface-500 hover:text-surface-700"
        >
          <i class="pi pi-arrow-left" />
        </NuxtLink>
        <div>
          <h1 class="font-bold text-surface-800 text-lg">{{ $t('shift.board.title') }}</h1>
          <p v-if="schedule" class="text-xs text-surface-500">{{ schedule.title }}</p>
        </div>
        <Tag v-if="schedule" :value="schedule.status" severity="info" class="text-xs" />
      </div>

      <div class="flex items-center gap-2">
        <!-- PDF エクスポートボタン（SUPPORTER ロールは非表示） -->
        <ShiftPdfExportButton
          v-if="!isSupporter"
          :schedule-id="scheduleId"
        />

        <!-- 変更依頼ページへのリンク -->
        <NuxtLink :to="`/teams/${teamId}/shifts/${scheduleId}/change-requests`">
          <Button
            :label="$t('shift.changeRequest.title')"
            icon="pi pi-list"
            severity="secondary"
            outlined
            size="small"
          />
        </NuxtLink>

        <Button
          :label="$t('shift.autoAssign.history')"
          icon="pi pi-history"
          severity="secondary"
          outlined
          size="small"
          @click="historyVisible = true"
        />
        <Button
          :label="$t('shift.autoAssign.button')"
          icon="pi pi-bolt"
          size="small"
          @click="autoAssignVisible = true"
        />
      </div>
    </div>

    <!-- メインコンテンツ -->
    <div class="flex flex-1 overflow-hidden">
      <!-- シフトボード -->
      <div class="flex-1 overflow-hidden">
        <ShiftBoardMatrix
          :slots="slots"
          :positions="positions"
          :local-assignments="localAssignments"
          :member-map="memberMap"
          :pending-run="pendingRun"
          :schedule-version="scheduleVersion"
          @drop-user="onDropUser"
          @remove-user="onRemoveUser"
          @add-user="addUserSlotId = $event; poolVisible = true"
          @confirm-auto-assign="onConfirmAutoAssignWithNote"
          @revoke-auto-assign="onRevokeAutoAssign"
        />
      </div>

      <!-- 未割当メンバープール -->
      <div class="w-60 flex-shrink-0">
        <ShiftMemberPool
          :members="unassignedMembers"
          @drag-start="draggingUserId = $event"
        />
      </div>
    </div>

    <!-- モーダル群 -->
    <ShiftAutoAssignModal
      v-model:visible="autoAssignVisible"
      :is-running="isRunning"
      @run="onRunAutoAssign"
    />

    <ShiftAutoAssignRunHistory
      v-if="historyVisible"
      :runs="runs"
    />

    <!-- メンバー追加ダイアログ（addUser 用） -->
    <Dialog
      v-model:visible="poolVisible"
      header="メンバーを追加"
      modal
      :style="{ width: '360px' }"
    >
      <div class="space-y-2">
        <div
          v-for="member in allMembers"
          :key="member.userId"
          class="flex items-center gap-3 p-2 rounded hover:bg-surface-50 cursor-pointer"
          @click="onAddUserFromDialog(member.userId)"
        >
          <Avatar
            v-if="member.avatarUrl"
            :image="member.avatarUrl"
            size="small"
            shape="circle"
          />
          <Avatar v-else :label="member.displayName.charAt(0)" size="small" shape="circle" />
          <span class="text-sm">{{ member.displayName }}</span>
        </div>
      </div>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import type {
  ShiftSlotResponse,
  ShiftPositionResponse,
  ShiftScheduleResponse,
  AssignmentRun,
  AssignmentParameters,
  AssignmentStrategyType,
} from '~/types/shift'

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const scheduleId = computed(() => Number(route.params.scheduleId))

const { currentUser } = useAuth()
const isSupporter = computed(() => currentUser.value?.role === 'SUPPORTER')

const shiftApi = useShiftApi()
const { localAssignments, initSlot, moveUser, addUser, removeUser } = useShiftBoard(scheduleId)
const { runs, currentRun, isRunning, runAutoAssign, confirmAutoAssign, revokeAutoAssign, fetchRuns } =
  useAutoAssign(scheduleId)

// データ
const schedule = ref<ShiftScheduleResponse | null>(null)
const slots = ref<ShiftSlotResponse[]>([])
const positions = ref<ShiftPositionResponse[]>([])
const allMembers = ref<Array<{ userId: number; displayName: string; avatarUrl: string | null }>>([])
const scheduleVersion = ref(0)

// UI 状態
const autoAssignVisible = ref(false)
const historyVisible = ref(false)
const poolVisible = ref(false)
const addUserSlotId = ref<number | null>(null)
const draggingUserId = ref<number | null>(null)

// 未確認の自動割当結果
const pendingRun = computed((): AssignmentRun | null => {
  const succeeded = runs.value.find((r) => r.status === 'SUCCEEDED')
  return succeeded ?? null
})

// メンバーマップ（userId -> メンバー情報）
const memberMap = computed(() => {
  const map: Record<number, { userId: number; displayName: string; avatarUrl: string | null }> = {}
  allMembers.value.forEach((m) => {
    map[m.userId] = m
  })
  return map
})

// 未割当メンバー（全スロットに割り当て済みでないメンバー）
const assignedUserIds = computed(() => {
  const ids = new Set<number>()
  Object.values(localAssignments.value).forEach((userIds) => {
    userIds.forEach((id) => ids.add(id))
  })
  slots.value.forEach((slot) => {
    const local = localAssignments.value[slot.id]
    if (local === undefined) {
      slot.assignedMembers.forEach((m) => ids.add(m.userId))
    }
  })
  return ids
})

const unassignedMembers = computed(() =>
  allMembers.value.filter((m) => !assignedUserIds.value.has(m.userId)),
)

// 初期データ取得
onMounted(async () => {
  await Promise.all([loadSchedule(), loadSlots(), loadPositions(), fetchRuns()])
})

async function loadSchedule(): Promise<void> {
  const res = await shiftApi.getShiftSchedule(scheduleId.value)
  schedule.value = (res as { data: ShiftScheduleResponse }).data
}

async function loadSlots(): Promise<void> {
  const res = await shiftApi.getShiftSlots(scheduleId.value)
  const data = (res as { data: ShiftSlotResponse[] }).data
  slots.value = data
  // ローカル状態を初期化
  data.forEach((slot) => {
    initSlot(
      slot.id,
      slot.assignedMembers.map((m) => m.userId),
    )
  })
  // メンバー一覧を収集
  const memberSet = new Map<number, { userId: number; displayName: string; avatarUrl: string | null }>()
  data.forEach((slot) => {
    slot.assignedMembers.forEach((m) => {
      memberSet.set(m.userId, m)
    })
  })
  allMembers.value = Array.from(memberSet.values())
}

async function loadPositions(): Promise<void> {
  const res = await shiftApi.getPositions()
  positions.value = (res as { data: ShiftPositionResponse[] }).data
}

// D&D でドロップ
async function onDropUser(payload: {
  fromSlotId: number | null
  toSlotId: number
  userId: number
}): Promise<void> {
  const { fromSlotId, toSlotId, userId } = payload
  const toSlot = slots.value.find((s) => s.id === toSlotId)
  const toVersion = toSlot ? 0 : 0 // バックエンドの楽観ロックバージョン（実際はAPIレスポンスで更新）

  if (fromSlotId !== null) {
    await moveUser(fromSlotId, toSlotId, userId, toVersion)
  } else {
    await addUser(toSlotId, userId, toVersion)
  }
}

// メンバー削除
async function onRemoveUser(payload: { slotId: number; userId: number }): Promise<void> {
  await removeUser(payload.slotId, payload.userId, 0)
}

// ダイアログからメンバー追加
async function onAddUserFromDialog(userId: number): Promise<void> {
  if (addUserSlotId.value !== null) {
    await addUser(addUserSlotId.value, userId, 0)
    poolVisible.value = false
  }
}

// 自動割当実行
async function onRunAutoAssign(
  strategy: AssignmentStrategyType,
  params: AssignmentParameters,
): Promise<void> {
  await runAutoAssign(strategy, params)
  autoAssignVisible.value = false
}

// 自動割当確定（目視確認後）
async function onConfirmAutoAssignWithNote(note: string | undefined): Promise<void> {
  const run = pendingRun.value
  if (!run) return
  const assignmentIds = (run.assignments ?? []).map((a) => a.id)
  await confirmAutoAssign(run.id, assignmentIds, scheduleVersion.value)
  // スロット情報を再取得
  await loadSlots()
}

// 自動割当破棄
async function onRevokeAutoAssign(): Promise<void> {
  await revokeAutoAssign()
}
</script>
