<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

interface InviteToken {
  id: number
  token: string
  roleName: string
  expiresAt: string | null
  maxUses: number | null
  usedCount: number
  revokedAt: string | null
  createdAt: string
}

const api = useApi()
const notification = useNotification()
const tokens = ref<InviteToken[]>([])
const loading = ref(false)
const showCreateDialog = ref(false)

// 作成フォーム
const newToken = ref({
  roleId: 4, // MEMBER default
  expiresIn: '7d' as string | null,
  maxUses: null as number | null,
})

const expiresOptions = [
  { label: '1日', value: '1d' },
  { label: '7日', value: '7d' },
  { label: '30日', value: '30d' },
  { label: '90日', value: '90d' },
  { label: '無期限', value: null },
]

const roleOptions = [
  { label: 'メンバー', value: 4 },
  { label: 'サポーター', value: 5 },
  { label: 'ゲスト', value: 6 },
]

async function loadTokens() {
  loading.value = true
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    const response = await api<{ data: InviteToken[] }>(
      `/api/v1/${base}/${props.scopeId}/invite-tokens`
    )
    tokens.value = response.data
  }
  catch {
    tokens.value = []
  }
  finally {
    loading.value = false
  }
}

async function createToken() {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/invite-tokens`, {
      method: 'POST',
      body: newToken.value,
    })
    notification.success('招待リンクを作成しました')
    showCreateDialog.value = false
    newToken.value = { roleId: 4, expiresIn: '7d', maxUses: null }
    await loadTokens()
  }
  catch {
    notification.error('招待リンクの作成に失敗しました')
  }
}

async function revokeToken(tokenId: number) {
  if (!confirm('この招待リンクを無効にしますか？')) return
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/invite-tokens/${tokenId}`, { method: 'DELETE' })
    notification.success('招待リンクを無効にしました')
    await loadTokens()
  }
  catch {
    notification.error('操作に失敗しました')
  }
}

function copyInviteUrl(token: string) {
  const url = `${window.location.origin}/invite/${token}`
  navigator.clipboard.writeText(url)
  notification.success('招待URLをコピーしました')
}

function isExpired(token: InviteToken): boolean {
  if (token.revokedAt) return true
  if (token.expiresAt && new Date(token.expiresAt) < new Date()) return true
  if (token.maxUses && token.usedCount >= token.maxUses) return true
  return false
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '無期限'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

onMounted(() => loadTokens())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">招待リンク</h3>
      <Button
        label="新規作成"
        icon="pi pi-plus"
        size="small"
        @click="showCreateDialog = true"
      />
    </div>

    <DataTable :value="tokens" :loading="loading" data-key="id">
      <Column header="ロール" style="width: 120px">
        <template #body="{ data }">
          <RoleBadge :role="data.roleName" />
        </template>
      </Column>
      <Column header="有効期限" style="width: 120px">
        <template #body="{ data }">
          <span :class="{ 'text-red-500 line-through': isExpired(data) }">
            {{ formatDate(data.expiresAt) }}
          </span>
        </template>
      </Column>
      <Column header="使用回数" style="width: 120px">
        <template #body="{ data }">
          {{ data.usedCount }} / {{ data.maxUses ?? '∞' }}
        </template>
      </Column>
      <Column header="操作" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="!isExpired(data)"
              v-tooltip.top="'URLをコピー'"
              icon="pi pi-copy"
              text
              rounded
              size="small"
              @click="copyInviteUrl(data.token)"
            />
            <Button
              v-if="!isExpired(data)"
              v-tooltip.top="'無効にする'"
              icon="pi pi-times"
              severity="danger"
              text
              rounded
              size="small"
              @click="revokeToken(data.id)"
            />
          </div>
        </template>
      </Column>
      <template #empty>
        <div class="p-4 text-center text-surface-500">
          招待リンクはまだありません
        </div>
      </template>
    </DataTable>

    <!-- 作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="招待リンクを作成"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">招待ロール</label>
          <Select
            v-model="newToken.roleId"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">有効期限</label>
          <Select
            v-model="newToken.expiresIn"
            :options="expiresOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">最大使用回数（空欄で無制限）</label>
          <InputNumber v-model="newToken.maxUses" :min="1" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" @click="createToken" />
      </template>
    </Dialog>
  </div>
</template>
