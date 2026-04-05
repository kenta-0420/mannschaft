<script setup lang="ts">
import type { ContactRequestBlockResponse } from '~/types/contact'

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const blocks = ref<ContactRequestBlockResponse[]>([])
const loading = ref(false)

async function fetchBlocks() {
  loading.value = true
  try {
    const result = await contactApi.listRequestBlocks()
    blocks.value = result.data
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestBlockList: 一覧取得' })
  } finally {
    loading.value = false
  }
}

async function remove(blockedUserId: number) {
  try {
    await contactApi.removeRequestBlock(blockedUserId)
    blocks.value = blocks.value.filter((b) => b.blockedUser.id !== blockedUserId)
    notification.success('拒否設定を解除しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestBlockList: 拒否解除' })
    notification.error('解除に失敗しました')
  }
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

onMounted(fetchBlocks)
</script>

<template>
  <div class="flex flex-col gap-3">
    <p class="text-sm text-gray-500">
      申請事前拒否リストに登録したユーザーからの連絡先追加申請は、自動的に無視されます。
      相手には通知されません。
    </p>

    <PageLoading v-if="loading" />

    <div v-else-if="blocks.length === 0" class="py-6 text-center text-sm text-gray-400">
      申請を事前拒否しているユーザーはいません
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="block in blocks"
        :key="block.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3"
      >
        <Avatar
          :image="block.blockedUser.avatarUrl ?? undefined"
          :label="block.blockedUser.avatarUrl ? undefined : block.blockedUser.displayName.charAt(0)"
          shape="circle"
        />
        <div class="min-w-0 flex-1">
          <div class="font-medium">{{ block.blockedUser.displayName }}</div>
          <div v-if="block.blockedUser.contactHandle" class="text-xs text-gray-400">
            @{{ block.blockedUser.contactHandle }}
          </div>
          <div class="text-xs text-gray-400">{{ formatDate(block.createdAt) }}に設定</div>
        </div>
        <Button
          label="解除"
          size="small"
          severity="secondary"
          outlined
          @click="remove(block.blockedUser.id)"
        />
      </div>
    </div>
  </div>
</template>
