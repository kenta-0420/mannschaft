<script setup lang="ts">
import type { ShiftScheduleResponse } from '~/types/shift'
import { isAcceptingShiftRequests } from '~/utils/shiftStatus'

const props = defineProps<{
  /** チームの URL 識別子（slug）。API 呼び出しとシフトボードへの遷移に使う */
  teamId: string
  canManage: boolean
  /**
   * シフトボードへの導線を出してよいか（当該チームの ADMIN / DEPUTY_ADMIN）。
   * 省略時は出さない（既存の利用箇所を壊さないため既定 false）。
   */
  canManageBoard?: boolean
}>()

const emit = defineEmits<{
  select: [scheduleId: number]
  create: []
}>()

const { t } = useI18n()
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

// ステータスバッジの表示定義。ラベルは i18n キー経由で引く（直書き禁止）。
// CMP-260826-2127 / AC-15 で非管理者にも COLLECTING / ADJUSTING / ARCHIVED が
// 並ぶようになったため、日本語以外のロケールで新たに見える状態名が
// 未翻訳になっていた。キーは pages/shift/index.vue の statusOptions と共通。
const statusConfig = computed<Record<string, { label: string; severity: string }>>(() => ({
  DRAFT: { label: t('shift.status.draft'), severity: 'secondary' },
  COLLECTING: { label: t('shift.status.collecting'), severity: 'info' },
  ADJUSTING: { label: t('shift.status.adjusting'), severity: 'warn' },
  PUBLISHED: { label: t('shift.status.published'), severity: 'success' },
  ARCHIVED: { label: t('shift.status.archived'), severity: 'contrast' },
}))

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

/**
 * 行クリック（1日ずつ入力する希望ダイアログ）。
 * 受付終了のシフト表では BE に必ず弾かれるため開かせない。
 */
function onRowClick(s: ShiftScheduleResponse) {
  if (!isAcceptingShiftRequests(s)) return
  emit('select', s.id)
}

function goToBulkRequest() {
  navigateTo('/my/shift-request')
}

function goToBoard(scheduleId: number) {
  navigateTo(`/teams/${props.teamId}/shifts/${scheduleId}/board`)
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
        class="rounded-lg border border-surface-300 p-4 transition-shadow hover:shadow-md dark:border-surface-600"
        :class="isAcceptingShiftRequests(s) ? 'cursor-pointer' : 'cursor-default'"
        @click="onRowClick(s)"
      >
        <div class="flex items-center justify-between">
          <div>
            <p class="font-medium">{{ s.content.title }}</p>
            <p class="text-xs text-surface-500">{{ s.period.startDate }} 〜 {{ s.period.endDate }}</p>
          </div>
          <div class="flex items-center gap-2">
            <Tag
              :value="statusConfig[s.status.status]?.label ?? s.status.status"
              :severity="statusConfig[s.status.status]?.severity ?? 'secondary'"
              rounded
            />
            <div v-if="canManage" class="flex gap-1" @click.stop>
              <Button
                v-if="s.status.status === 'ADJUSTING'"
                v-tooltip="'公開'"
                icon="pi pi-send"
                text
                rounded
                size="small"
                @click="publish(s.id)"
              />
              <Button
                v-if="s.status.status === 'PUBLISHED'"
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

        <!--
          行内の導線（CMP-260908-2118）。
          クリックは親（行クリック＝希望ダイアログ）へ伝播させない。
          ラッパー div の @click.stop で、内側の Button のクリックを含め
          この領域で発生したクリックをすべてここで止める。
        -->
        <div class="mt-3 flex flex-wrap items-center gap-2" @click.stop>
          <Button
            v-if="isAcceptingShiftRequests(s)"
            :label="t('shift.entry.bulkRequest')"
            icon="pi pi-list-check"
            size="small"
            outlined
            @click="goToBulkRequest()"
          />
          <span v-else class="text-xs text-surface-500">
            {{ t('shift.entry.closed') }}
          </span>
          <Button
            v-if="canManageBoard"
            :label="t('shift.entry.board')"
            icon="pi pi-th-large"
            size="small"
            severity="secondary"
            outlined
            @click="goToBoard(s.id)"
          />
        </div>
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-table"
      :message="t('shift.empty.noSchedules')"
    />
  </div>
</template>
