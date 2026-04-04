<script setup lang="ts">
import type { HandleSearchResult } from '~/types/contact'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  added: []
}>()

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const handle = ref('')
const searching = ref(false)
const sending = ref(false)
const result = ref<HandleSearchResult | null>(null)
const notFound = ref(false)
const message = ref('')

let searchTimer: ReturnType<typeof setTimeout> | null = null

async function search() {
  const trimmed = handle.value.trim().replace(/^@/, '')
  if (!trimmed || trimmed.length < 3) {
    result.value = null
    notFound.value = false
    return
  }
  searching.value = true
  notFound.value = false
  result.value = null
  try {
    const res = await contactApi.searchByHandle(trimmed)
    result.value = res.data
    if (!res.data) notFound.value = true
  } catch (e) {
    captureQuiet(e, { context: 'ContactSearchDialog: ハンドル検索' })
    notFound.value = true
  } finally {
    searching.value = false
  }
}

watch(handle, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(search, 500)
})

async function sendRequest() {
  if (!result.value) return
  sending.value = true
  try {
    await contactApi.sendRequest({
      targetUserId: result.value.userId,
      message: message.value || undefined,
      sourceType: 'HANDLE_SEARCH',
    })
    notification.success('申請を送信しました')
    emit('update:visible', false)
    emit('added')
  } catch (e) {
    captureQuiet(e, { context: 'ContactSearchDialog: 申請送信' })
    notification.error('申請に失敗しました')
  } finally {
    sending.value = false
  }
}

function onHide() {
  handle.value = ''
  result.value = null
  notFound.value = false
  message.value = ''
}
</script>

<template>
  <Dialog
    :visible="visible"
    header="@ハンドルで連絡先を追加"
    :style="{ width: '400px' }"
    modal
    @update:visible="emit('update:visible', $event)"
    @hide="onHide"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">@ハンドルで検索</label>
        <div class="relative flex items-center">
          <span class="pointer-events-none absolute left-3 text-gray-400">@</span>
          <InputText v-model="handle" class="w-full pl-7" placeholder="handle_name" autofocus />
          <i v-if="searching" class="pi pi-spin pi-spinner absolute right-3 text-gray-400" />
        </div>
      </div>

      <div
        v-if="notFound"
        class="rounded-lg border border-dashed border-gray-200 p-4 text-center text-sm text-gray-400"
      >
        見つかりませんでした
      </div>

      <template v-if="result">
        <div class="flex items-center gap-3 rounded-lg border border-surface-200 p-3">
          <Avatar
            :image="result.avatarUrl ?? undefined"
            :label="result.avatarUrl ? undefined : result.displayName.charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <div class="font-medium">{{ result.displayName }}</div>
            <div class="text-xs text-gray-400">@{{ result.contactHandle }}</div>
          </div>
          <Tag v-if="result.isContact" value="連絡先" severity="success" />
          <Tag v-else-if="result.hasPendingRequest" value="申請中" severity="info" />
        </div>

        <template v-if="!result.isContact && !result.hasPendingRequest">
          <div>
            <label class="mb-1 block text-sm font-medium">一言メッセージ（任意）</label>
            <Textarea
              v-model="message"
              rows="2"
              maxlength="200"
              placeholder="よろしくお願いします"
              class="w-full"
              auto-resize
            />
            <small class="text-gray-400">{{ message.length }}/200</small>
          </div>
          <div v-if="result.contactApprovalRequired" class="text-xs text-gray-400">
            <i class="pi pi-info-circle mr-1" />承認制のため、相手が承認すると連絡先に追加されます
          </div>
          <Button
            label="連絡先に追加"
            icon="pi pi-user-plus"
            class="w-full"
            :loading="sending"
            @click="sendRequest"
          />
        </template>
      </template>
    </div>
  </Dialog>
</template>
