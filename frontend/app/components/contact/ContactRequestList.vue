<script setup lang="ts">
import type { ContactRequestResponse } from '~/types/contact'

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const received = ref<ContactRequestResponse[]>([])
const sent = ref<ContactRequestResponse[]>([])
const loading = ref(false)
const activeTab = ref<'received' | 'sent'>('received')

async function fetchRequests() {
  loading.value = true
  try {
    const [recv, snt] = await Promise.all([
      contactApi.listReceivedRequests(),
      contactApi.listSentRequests(),
    ])
    received.value = recv.data
    sent.value = snt.data
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestList: 申請一覧取得' })
  } finally {
    loading.value = false
  }
}

async function accept(id: number) {
  try {
    await contactApi.acceptRequest(id)
    received.value = received.value.filter((r) => r.id !== id)
    notification.success('連絡先に追加しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestList: 申請承認' })
    notification.error('承認に失敗しました')
  }
}

async function reject(id: number) {
  try {
    await contactApi.rejectRequest(id)
    received.value = received.value.filter((r) => r.id !== id)
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestList: 申請拒否' })
    notification.error('操作に失敗しました')
  }
}

async function cancel(id: number) {
  try {
    await contactApi.cancelRequest(id)
    sent.value = sent.value.filter((r) => r.id !== id)
    notification.success('申請を取り消しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestList: 申請取り消し' })
    notification.error('取り消しに失敗しました')
  }
}

onMounted(fetchRequests)
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="flex gap-1 rounded-lg bg-surface-100 p-1">
      <button
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
        :class="
          activeTab === 'received' ? 'bg-white shadow-sm' : 'text-gray-500 hover:text-gray-700'
        "
        @click="activeTab = 'received'"
      >
        受信
        <Badge v-if="received.length > 0" :value="received.length" class="ml-1" />
      </button>
      <button
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
        :class="activeTab === 'sent' ? 'bg-white shadow-sm' : 'text-gray-500 hover:text-gray-700'"
        @click="activeTab = 'sent'"
      >
        送信済み
      </button>
    </div>

    <PageLoading v-if="loading" />

    <template v-else-if="activeTab === 'received'">
      <div v-if="received.length === 0" class="py-6 text-center text-sm text-gray-400">
        受信中の申請はありません
      </div>
      <ContactRequestItem
        v-for="req in received"
        :key="req.id"
        :request="req"
        type="received"
        @accept="accept"
        @reject="reject"
      />
    </template>

    <template v-else>
      <div v-if="sent.length === 0" class="py-6 text-center text-sm text-gray-400">
        送信済みの申請はありません
      </div>
      <ContactRequestItem
        v-for="req in sent"
        :key="req.id"
        :request="req"
        type="sent"
        @cancel="cancel"
      />
    </template>
  </div>
</template>
