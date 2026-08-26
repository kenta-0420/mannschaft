<script setup lang="ts">
import type { ContactInviteTokenResponse, CreateInviteTokenBody } from '~/types/contact'

const { t } = useI18n()
const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()
const { formatDate } = useDatetime()

const tokens = ref<ContactInviteTokenResponse[]>([])
const loading = ref(false)
const creating = ref(false)
const showCreateForm = defineModel<boolean>('showCreateForm', { default: false })

const form = ref<CreateInviteTokenBody>({
  label: '',
  maxUses: 1,
  expiresIn: '7d',
})

const expiresInOptions = computed(() => [
  { label: t('contact_invite.expires_options.one_day'), value: '1d' },
  { label: t('contact_invite.expires_options.seven_days'), value: '7d' },
  { label: t('contact_invite.expires_options.thirty_days'), value: '30d' },
  { label: t('contact_invite.expires_options.never'), value: null },
])
const maxUsesOptions = computed(() => [
  { label: t('contact_invite.max_uses_options.once'), value: 1 },
  { label: t('contact_invite.max_uses_options.five'), value: 5 },
  { label: t('contact_invite.max_uses_options.ten'), value: 10 },
  { label: t('contact_invite.max_uses_options.fifty'), value: 50 },
  { label: t('contact_invite.max_uses_options.unlimited'), value: null },
])

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
    notification.success(t('contact_invite.messages.create_success'))
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvitePanel: トークン発行' })
    notification.error(t('contact_invite.messages.create_error'))
  } finally {
    creating.value = false
  }
}

async function revokeToken(id: number) {
  try {
    await contactApi.revokeInviteToken(id)
    tokens.value = tokens.value.filter((t2) => t2.id !== id)
    notification.success(t('contact_invite.messages.revoke_success'))
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvitePanel: トークン無効化' })
    notification.error(t('contact_invite.messages.revoke_error'))
  }
}

async function copyUrl(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    notification.success(t('contact_invite.messages.copy_success'))
  } catch {
    notification.error(t('contact_invite.messages.copy_error'))
  }
}

function formatExpiry(token: ContactInviteTokenResponse): string {
  if (!token.expiresAt) return t('contact_invite.expiry.never')
  const d = new Date(token.expiresAt)
  if (d < new Date()) return t('contact_invite.expiry.expired')
  return t('contact_invite.expiry.until', { date: formatDate(token.expiresAt) })
}

onMounted(fetchTokens)
</script>

<template>
  <div class="flex flex-col gap-4">
    <div v-if="showCreateForm" class="rounded-lg border border-surface-300 p-4">
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('contact_invite.form.label') }}</label>
          <InputText
            v-model="form.label"
            :placeholder="t('contact_invite.form.label_placeholder')"
            class="w-full"
            maxlength="50"
          />
        </div>
        <div class="flex gap-3">
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">{{ t('contact_invite.form.max_uses') }}</label>
            <Select
              v-model="form.maxUses"
              :options="maxUsesOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div class="flex-1">
            <label class="mb-1 block text-sm font-medium">{{ t('contact_invite.form.expires_in') }}</label>
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
            :label="t('contact_invite.form.submit')"
            icon="pi pi-link"
            class="flex-1"
            :loading="creating"
            @click="createToken"
          />
          <Button
            :label="t('contact_invite.form.cancel')"
            severity="secondary"
            outlined
            @click="showCreateForm = false"
          />
        </div>
      </div>
    </div>

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="tokens.length === 0"
      icon="pi pi-link"
      :message="t('contact_invite.list.empty')"
    />

    <div v-else class="flex flex-col gap-2">
      <div v-for="token in tokens" :key="token.id" class="rounded-lg border border-surface-300 p-3">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-medium">{{ token.label || t('contact_invite.list.no_label') }}</span>
          <Button
            v-tooltip.top="t('contact_invite.list.revoke_tooltip')"
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
            ><i class="pi pi-users mr-1" />{{ token.usedCount }}/{{ token.maxUses ?? '∞' }}{{ t('contact_invite.list.uses_unit') }}</span
          >
          <span><i class="pi pi-calendar mr-1" />{{ formatExpiry(token) }}</span>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <QrCodeImage
            :value="token.inviteUrl"
            :size="64"
          />
          <span class="text-xs text-gray-400">{{ t('contact_invite.list.qr_hint') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
