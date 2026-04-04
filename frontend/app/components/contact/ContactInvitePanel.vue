<script setup lang="ts">
import type { ContactInviteTokenResponse, CreateInviteTokenBody } from '~/types/contact'

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const tokens = ref<ContactInviteTokenResponse[]>([])
const loading = ref(false)
const creating = ref(false)
const showCreateForm = ref(false)

const form = ref<CreateInviteTokenBody>({
  label: '',
  maxUses: 1,
  expiresIn: '7d',
})

const expiresInOptions = [
  { label: '1日', value: '1d' },
  { label: '7日', value: '7d' },
  { label: '30日', value: '30d' },
  { label: '無期限', value: null },
]
const maxUsesOptions = [
  { label: '1回', value: 1 },
  { label: '5回', value: 5 },
  { label: '10回', value: 10 },
  { label: '50回', value: 50 },
  { label: '無制限', value: null },
]

async function fetchTokens() {
  loading.value = true
  try {
    const result = await contactApi.listInviteTokens()
    tokens.value = result.data
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvitePanel: トークン一覧取得' })
  } finally {
    loading.value = false
  }
}

async function createToken() {
  creating.value = true
  try {
    const result = await contactApi.createInviteToken({
      label: form.value.label || undefined,
      maxUses: form.value.maxUses ?? undefined,
      expiresIn: form.value.expiresIn ?? undefined,
    })
    tokens.value.unshift(result.data)
    showCreateForm.value = false
    form.value = { label: '', maxUses: 1, expiresIn: '7d' }
    notification.success('招待URLを発行しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvitePanel: トークン発行' })
    notification.error('発行に失敗しました')
  } finally {
    creating.value = false
  }
}

async function revokeToken(id: number) {
  try {
    await contactApi.revokeInviteToken(id)
    tokens.value = tokens.value.filter((t) => t.id !== id)
    notification.success('招待URLを無効化しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvitePanel: トークン無効化' })
    notification.error('無効化に失敗しました')
  }
}

async function copyUrl(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    notification.success('URLをコピーしました')
  } catch {
    notification.error('コピーに失敗しました')
  }
}

function formatExpiry(token: ContactInviteTokenResponse) {
  if (!token.expiresAt) return '無期限'
  const d = new Date(token.expiresAt)
  if (d < new Date()) return '期限切れ'
  return d.toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' }) + ' まで'
}

onMounted(fetchTokens)
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="flex items-center justify-between">
      <h3 class="font-semibold">招待URL</h3>
      <Button
        label="新しいURLを発行"
        icon="pi pi-plus"
        size="small"
        @click="showCreateForm = !showCreateForm"
      />
    </div>

    <div v-if="showCreateForm" class="rounded-lg border border-surface-200 p-4">
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">ラベル（任意）</label>
          <InputText v-model="form.label" placeholder="SNS用など" class="w-full" maxlength="50" />
        </div>
        <div class="flex gap-3">
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">利用回数</label>
            <Select
              v-model="form.maxUses"
              :options="maxUsesOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">有効期限</label>
            <Select
              v-model="form.expiresIn"
              :options="expiresInOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>
        <div class="flex gap-2">
          <Button
            label="発行"
            icon="pi pi-link"
            class="flex-1"
            :loading="creating"
            @click="createToken"
          />
          <Button
            label="キャンセル"
            severity="secondary"
            outlined
            @click="showCreateForm = false"
          />
        </div>
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else-if="tokens.length === 0" class="py-6 text-center text-sm text-gray-400">
      発行済みの招待URLはありません
    </div>

    <div v-else class="flex flex-col gap-2">
      <div v-for="token in tokens" :key="token.id" class="rounded-lg border border-surface-200 p-3">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-medium">{{ token.label || '（ラベルなし）' }}</span>
          <Button
            v-tooltip.top="'無効化'"
            icon="pi pi-trash"
            size="small"
            text
            rounded
            severity="danger"
            @click="revokeToken(token.id)"
          />
        </div>
        <div class="mb-2 flex items-center gap-2 rounded bg-surface-50 px-2 py-1">
          <span class="min-w-0 flex-1 truncate text-xs text-gray-600">{{ token.inviteUrl }}</span>
          <Button icon="pi pi-copy" size="small" text rounded @click="copyUrl(token.inviteUrl)" />
        </div>
        <div class="flex items-center gap-3 text-xs text-gray-400">
          <span
            ><i class="pi pi-users mr-1" />{{ token.usedCount }}/{{ token.maxUses ?? '∞' }}回</span
          >
          <span><i class="pi pi-calendar mr-1" />{{ formatExpiry(token) }}</span>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <img
            :src="token.qrCodeUrl"
            alt="QRコード"
            class="h-16 w-16 rounded border border-surface-200"
          />
          <span class="text-xs text-gray-400">QRコードをスキャンして追加</span>
        </div>
      </div>
    </div>
  </div>
</template>
