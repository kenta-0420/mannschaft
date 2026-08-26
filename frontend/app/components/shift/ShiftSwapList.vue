<script setup lang="ts">
import type { SwapRequestResponse } from '~/types/shift'

const props = defineProps<{
  /** 対象チームの数値ID。slug 文字列ではない（BEは `@RequestParam Long teamId` で受ける） */
  teamId: number
}>()

const shiftApi = useShiftApi()
const authStore = useAuthStore()
const notification = useNotification()

const swaps = ref<SwapRequestResponse[]>([])
const loading = ref(true)
/** 取得失敗を握りつぶさず保持する。空表示と失敗を利用者が区別できるようにする */
const loadFailed = ref(false)

const statusConfig: Record<string, { label: string; severity: string }> = {
  PENDING: { label: '保留中', severity: 'warn' },
  ACCEPTED: { label: '承認', severity: 'success' },
  REJECTED: { label: '却下', severity: 'danger' },
  CANCELLED: { label: 'キャンセル', severity: 'secondary' },
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
    notification.error('交換リクエストの取得に失敗しました')
    console.error('[ShiftSwapList] 交換リクエスト取得に失敗', e)
  } finally {
    loading.value = false
  }
}

async function accept(id: number) {
  await shiftApi.acceptSwap(id)
  notification.success('交換を承認しました')
  await load()
}

async function reject(id: number) {
  await shiftApi.resolveSwap(id, { action: 'reject' })
  notification.success('交換を却下しました')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <h3 class="mb-3 text-lg font-semibold">シフト交換リクエスト</h3>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="swaps.length > 0" class="space-y-2">
      <div
        v-for="swap in swaps"
        :key="swap.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div class="min-w-0 flex-1">
          <p class="text-sm">
            <span class="font-medium">申請者 #{{ swap.requesterId }}</span>
            <span class="text-surface-500"> → </span>
            <span class="font-medium">対象 #{{ swap.accepterId ?? '未定' }}</span>
          </p>
          <p class="text-xs text-surface-500">スロット #{{ swap.slotId }}</p>
          <p v-if="swap.reason" class="text-xs text-surface-400">理由: {{ swap.reason }}</p>
        </div>
        <Tag
          :value="statusConfig[swap.status]?.label ?? swap.status"
          :severity="statusConfig[swap.status]?.severity ?? 'secondary'"
          rounded
        />
        <div
          v-if="swap.status === 'PENDING' && swap.accepterId === authStore.currentUser?.id"
          class="flex gap-1"
        >
          <Button
            icon="pi pi-check"
            severity="success"
            text
            rounded
            size="small"
            @click="accept(swap.id)"
          />
          <Button
            icon="pi pi-times"
            severity="danger"
            text
            rounded
            size="small"
            @click="reject(swap.id)"
          />
        </div>
      </div>
    </div>
    <div
      v-else-if="loadFailed"
      class="rounded-lg border border-red-300 p-4 text-sm dark:border-red-700"
    >
      <p class="mb-2 text-red-600 dark:text-red-400">
        <i class="pi pi-exclamation-triangle mr-1" />交換リクエストを取得できませんでした
      </p>
      <Button label="再読み込み" icon="pi pi-refresh" size="small" outlined @click="load" />
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-arrow-right-arrow-left"
      message="交換リクエストはありません"
    />
  </div>
</template>
