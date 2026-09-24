<script setup lang="ts">
import type { SwapRequestResponse } from '~/types/shift'

const props = defineProps<{
  /** 対象チームの数値ID。slug 文字列ではない（BEは `@RequestParam Long teamId` で受ける） */
  teamId: number
  /**
   * 当該チームの管理者（ADMIN / DEPUTY_ADMIN）か。
   * 承認・却下は BE で管理者に限定されているため、UI の出し分けもこれに揃える。
   */
  canManage?: boolean
}>()

const shiftApi = useShiftApi()
const authStore = useAuthStore()
const notification = useNotification()
const { t } = useI18n()

const swaps = ref<SwapRequestResponse[]>([])
const loading = ref(true)
/** 取得失敗を握りつぶさず保持する。空表示と失敗を利用者が区別できるようにする */
const loadFailed = ref(false)

const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

const statusConfig: Record<string, { labelKey: string; severity: string }> = {
  PENDING: { labelKey: 'shift.swap.list.statusPending', severity: 'warn' },
  ACCEPTED: { labelKey: 'shift.swap.list.statusAccepted', severity: 'info' },
  APPROVED: { labelKey: 'shift.swap.list.statusApproved', severity: 'success' },
  REJECTED: { labelKey: 'shift.swap.list.statusRejected', severity: 'danger' },
  CANCELLED: { labelKey: 'shift.swap.list.statusCancelled', severity: 'secondary' },
}

function statusLabel(status: string): string {
  const key = statusConfig[status]?.labelKey
  return key ? t(key) : status
}

/**
 * 承諾ボタンを出す条件。
 *
 * BE（`ShiftSwapService#acceptSwapRequest`）の認可は「当該チームのメンバー（SUPPORTER 不可）で
 * PENDING かつ申請者本人でないこと」であり、`accepterId` は<b>承諾した瞬間に初めて確定する</b>。
 * かつて `accepterId === 自分` を条件にしていたため、承諾前は常に null で誰にも押せなかった。
 * 一覧は BE 側で「自分に関係する依頼」だけに絞られて返るため、ここでは BE の認可条件に揃える。
 */
function canAccept(swap: SwapRequestResponse): boolean {
  return swap.status === 'PENDING' && swap.requesterId !== currentUserId.value
}

/** 承認・却下は管理者かつ ACCEPTED のみ（BE は ACCEPTED 以外を受け付けない）。 */
function canResolve(swap: SwapRequestResponse): boolean {
  return props.canManage === true && swap.status === 'ACCEPTED'
}

/** 申請者本人は PENDING の自分の依頼を取り下げられる。 */
function canCancel(swap: SwapRequestResponse): boolean {
  return swap.status === 'PENDING' && swap.requesterId === currentUserId.value
}

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    swaps.value = await shiftApi.listSwapRequests(props.teamId)
  } catch (e) {
    // 握りつぶさない。「0件」と「取得失敗」を同じ空表示に潰すと不具合が恒久的に隠れる
    swaps.value = []
    loadFailed.value = true
    notification.error(t('shift.swap.list.loadFailed'))
    console.error('[ShiftSwapList] 交換リクエスト取得に失敗', e)
  } finally {
    loading.value = false
  }
}

async function accept(id: number) {
  try {
    await shiftApi.acceptSwap(id)
    notification.success(t('shift.swap.list.acceptSucceeded'))
    await load()
  } catch (e) {
    notification.error(t('shift.swap.list.actionFailed'))
    console.error('[ShiftSwapList] 交代の承諾に失敗', e)
  }
}

async function approve(id: number) {
  try {
    // BE は大文字の APPROVE / REJECT を比較する（小文字を送ると必ず失敗する）
    await shiftApi.resolveSwap(id, { action: 'APPROVE' })
    notification.success(t('shift.swap.list.approveSucceeded'))
    await load()
  } catch (e) {
    notification.error(t('shift.swap.list.actionFailed'))
    console.error('[ShiftSwapList] 交代の承認に失敗', e)
  }
}

async function reject(id: number) {
  try {
    await shiftApi.resolveSwap(id, { action: 'REJECT' })
    notification.success(t('shift.swap.list.rejectSucceeded'))
    await load()
  } catch (e) {
    notification.error(t('shift.swap.list.actionFailed'))
    console.error('[ShiftSwapList] 交代の却下に失敗', e)
  }
}

async function cancel(id: number) {
  try {
    await shiftApi.deleteSwapRequest(id)
    notification.success(t('shift.swap.list.cancelSucceeded'))
    await load()
  } catch (e) {
    notification.error(t('shift.swap.list.actionFailed'))
    console.error('[ShiftSwapList] 交代の取り下げに失敗', e)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h3 class="mb-3 text-lg font-semibold">{{ t('shift.swap.list.title') }}</h3>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="swaps.length > 0" class="space-y-2">
      <div
        v-for="swap in swaps"
        :key="swap.id"
        :data-testid="`swap-row-${swap.id}`"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div class="min-w-0 flex-1">
          <p class="text-sm">
            <span class="font-medium">{{
              t('shift.swap.list.requester', { id: swap.requesterId })
            }}</span>
            <span class="text-surface-500"> → </span>
            <span class="font-medium">{{
              swap.accepterId
                ? t('shift.swap.list.accepter', { id: swap.accepterId })
                : t('shift.swap.list.accepterUndecided')
            }}</span>
          </p>
          <p class="text-xs text-surface-500">{{ t('shift.swap.list.slot', { id: swap.slotId }) }}</p>
          <p v-if="swap.reason" class="text-xs text-surface-400">
            {{ t('shift.swap.list.reason', { reason: swap.reason }) }}
          </p>
        </div>
        <Tag
          :value="statusLabel(swap.status)"
          :severity="statusConfig[swap.status]?.severity ?? 'secondary'"
          rounded
        />
        <div class="flex gap-1">
          <Button
            v-if="canAccept(swap)"
            :data-testid="`swap-accept-${swap.id}`"
            :label="t('shift.swap.accept')"
            icon="pi pi-check"
            severity="success"
            text
            rounded
            size="small"
            @click="accept(swap.id)"
          />
          <Button
            v-if="canResolve(swap)"
            :data-testid="`swap-approve-${swap.id}`"
            :label="t('shift.swap.approve')"
            icon="pi pi-verified"
            severity="success"
            text
            rounded
            size="small"
            @click="approve(swap.id)"
          />
          <Button
            v-if="canResolve(swap)"
            :data-testid="`swap-reject-${swap.id}`"
            :label="t('shift.swap.reject')"
            icon="pi pi-times"
            severity="danger"
            text
            rounded
            size="small"
            @click="reject(swap.id)"
          />
          <Button
            v-if="canCancel(swap)"
            :data-testid="`swap-cancel-${swap.id}`"
            :label="t('shift.swap.list.cancel')"
            icon="pi pi-undo"
            severity="secondary"
            text
            rounded
            size="small"
            @click="cancel(swap.id)"
          />
        </div>
      </div>
    </div>
    <div
      v-else-if="loadFailed"
      class="rounded-lg border border-red-300 p-4 text-sm dark:border-red-700"
    >
      <p class="mb-2 text-red-600 dark:text-red-400">
        <i class="pi pi-exclamation-triangle mr-1" />{{ t('shift.swap.list.loadFailedBody') }}
      </p>
      <Button
        :label="t('shift.swap.list.reload')"
        icon="pi pi-refresh"
        size="small"
        outlined
        @click="load"
      />
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-arrow-right-arrow-left"
      :message="t('shift.swap.list.empty')"
    />
  </div>
</template>
