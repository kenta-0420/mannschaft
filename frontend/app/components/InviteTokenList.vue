<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
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

const { t } = useI18n()
const api = useApi()
const { downloadInviteTokenPdf } = useInviteTokenApi()
const notification = useNotification()
const { formatDate } = useDatetime()
const tokens = ref<InviteToken[]>([])
const loading = ref(false)
const showCreateDialog = ref(false)

// ローディング状態（PDF生成中）
const pdfLoadingId = ref<number | null>(null)

// 作成フォーム
const newToken = ref({
  roleId: 4, // MEMBER default
  expiresIn: '7d' as string | null,
  maxUses: null as number | null,
})

const expiresOptions = computed(() => [
  { label: t('inviteToken.oneDay'), value: '1d' },
  { label: t('inviteToken.sevenDays'), value: '7d' },
  { label: t('inviteToken.thirtyDays'), value: '30d' },
  { label: t('inviteToken.ninetyDays'), value: '90d' },
  { label: t('inviteToken.never'), value: null },
])

const roleOptions = computed(() => [
  { label: t('role.MEMBER'), value: 4 },
  { label: t('role.SUPPORTER'), value: 5 },
  { label: t('role.GUEST'), value: 6 },
])

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
    notification.success(t('inviteToken.createSuccess'))
    showCreateDialog.value = false
    newToken.value = { roleId: 4, expiresIn: '7d', maxUses: null }
    await loadTokens()
  }
  catch {
    notification.error(t('inviteToken.createError'))
  }
}

async function revokeToken(tokenId: number) {
  if (!confirm(t('inviteToken.revokeConfirm'))) return
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/invite-tokens/${tokenId}`, { method: 'DELETE' })
    notification.success(t('inviteToken.revokeSuccess'))
    await loadTokens()
  }
  catch {
    notification.error(t('inviteToken.revokeError'))
  }
}

function copyInviteUrl(token: string) {
  const url = `${window.location.origin}/invite/${token}`
  navigator.clipboard.writeText(url)
  notification.success(t('inviteToken.copied'))
}

async function downloadQrPdf(tokenId: number) {
  pdfLoadingId.value = tokenId
  try {
    const response = await downloadInviteTokenPdf(props.scopeType, props.scopeId, tokenId)
    const url = URL.createObjectURL(response)
    const a = document.createElement('a')
    a.href = url
    a.download = ''
    a.click()
    URL.revokeObjectURL(url)
  }
  catch {
    notification.error(t('inviteToken.printQrPdfError'))
  }
  finally {
    pdfLoadingId.value = null
  }
}

function isExpired(token: InviteToken): boolean {
  if (token.revokedAt) return true
  if (token.expiresAt && new Date(token.expiresAt) < new Date()) return true
  if (token.maxUses && token.usedCount >= token.maxUses) return true
  return false
}

function formatTokenDate(dateStr: string | null): string {
  if (!dateStr) return t('inviteToken.never')
  return formatDate(dateStr)
}

onMounted(() => loadTokens())
</script>

<template>
  <div class="min-w-0 max-w-full">
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">{{ t('inviteToken.title') }}</h3>
      <Button
        :label="t('inviteToken.createNew')"
        class="!min-h-11 !min-w-11"
        icon="pi pi-plus"
        size="small"
        @click="showCreateDialog = true"
      />
    </div>

    <!-- 操作列を固定し、データ列は表の中で横スクロールして読む。 -->
    <DataTable
      :value="tokens"
      :loading="loading"
      data-key="id"
      class="max-w-full"
      table-class="min-w-[496px] sm:min-w-[680px]"
      scrollable
    >
      <Column :header="t('inviteToken.role')" style="width: 120px">
        <template #body="{ data }">
          <RoleBadge :role="data.roleName" />
        </template>
      </Column>
      <Column :header="t('inviteToken.expiry')" style="width: 120px">
        <template #body="{ data }">
          <span :class="{ 'text-red-500 line-through': isExpired(data) }">
            {{ formatTokenDate(data.expiresAt) }}
          </span>
        </template>
      </Column>
      <Column :header="t('inviteToken.uses')" style="width: 120px">
        <template #body="{ data }"> {{ data.usedCount }} / {{ data.maxUses ?? '∞' }} </template>
      </Column>
      <Column
        :header="t('label.actions')"
        header-class="w-[136px] whitespace-nowrap sm:w-80"
        body-class="w-[136px] sm:w-80"
        align-frozen="right"
        frozen
      >
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="!isExpired(data)"
              v-tooltip.top="t('inviteToken.copy')"
              :aria-label="t('inviteToken.copy')"
              class="!min-h-11 !min-w-11 shrink-0"
              icon="pi pi-copy"
              text
              rounded
              size="small"
              @click="copyInviteUrl(data.token)"
            />
            <Button
              v-if="props.scopeType === 'team'"
              :label="t('inviteToken.printQrPdf')"
              :aria-label="t('inviteToken.printQrPdf')"
              class="!min-h-11 !min-w-11 w-full shrink-0 whitespace-normal sm:w-auto sm:whitespace-nowrap"
              icon="pi pi-file-pdf"
              text
              rounded
              size="small"
              :loading="pdfLoadingId === data.id"
              @click="downloadQrPdf(data.id)"
            />
            <Button
              v-if="!isExpired(data)"
              v-tooltip.top="t('inviteToken.revoke')"
              :aria-label="t('inviteToken.revoke')"
              class="!min-h-11 !min-w-11 shrink-0"
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
          {{ t('inviteToken.empty') }}
        </div>
      </template>
    </DataTable>

    <!-- 作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('inviteToken.createTitle')"
      :close-button-props="{ 'aria-label': t('button.close') }"
      :pt="{ pcCloseButton: { root: { class: '!min-h-11 !min-w-11' } } }"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('inviteToken.inviteRole') }}</label>
          <Select
            v-model="newToken.roleId"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full"
            :pt="{ option: { class: 'min-h-11' } }"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('inviteToken.expiry') }}</label>
          <Select
            v-model="newToken.expiresIn"
            :options="expiresOptions"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full"
            :pt="{ option: { class: 'min-h-11' } }"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('inviteToken.maxUses') }}</label>
          <InputNumber v-model="newToken.maxUses" :min="1" class="w-full" input-class="min-h-11" />
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          class="!min-h-11 !min-w-11"
          text
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('button.create')"
          class="!min-h-11 !min-w-11"
          icon="pi pi-check"
          @click="createToken"
        />
      </template>
    </Dialog>
  </div>
</template>
