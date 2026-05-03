<script setup lang="ts">
import type { SwapRequestResponse } from '~/types/shift'

defineProps<{
  teamId: number
}>()

const shiftApi = useShiftApi()
const authStore = useAuthStore()
const notification = useNotification()

const swaps = ref<SwapRequestResponse[]>([])
const loading = ref(true)

const statusConfig: Record<string, { label: string; severity: string }> = {
  PENDING: { label: '保留中', severity: 'warn' },
  ACCEPTED: { label: '承認', severity: 'success' },
  REJECTED: { label: '却下', severity: 'danger' },
  CANCELLED: { label: 'キャンセル', severity: 'secondary' },
}

async function load() {
  loading.value = true
  try {
    swaps.value = await shiftApi.listSwapRequests()
  } catch {
    swaps.value = []
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
    <DashboardEmptyState
      v-else
      icon="pi pi-arrow-right-arrow-left"
      message="交換リクエストはありません"
    />
  </div>
</template>
