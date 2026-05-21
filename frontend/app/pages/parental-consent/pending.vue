<script setup lang="ts">
import type { InvitationResponse } from '@/types/parental-consent'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const { sendInvitation, getInvitations, cancelInvitation, getParents } = useParentalConsentApi()
const notification = useNotification()

const invitations = ref<InvitationResponse[]>([])
const loading = ref(true)
const parentEmail = ref('')
const sending = ref(false)

async function loadData() {
  loading.value = true
  try {
    const [invs, parents] = await Promise.all([getInvitations(), getParents()])
    invitations.value = invs
    // 承認済み保護者がいれば → ホームへ
    if (parents.length > 0) {
      await navigateTo('/')
    }
  } finally {
    loading.value = false
  }
}

async function handleSendInvitation() {
  if (!parentEmail.value) return
  sending.value = true
  try {
    await sendInvitation(parentEmail.value)
    parentEmail.value = ''
    notification.success(t('parental_consent.invite_sent'))
    await loadData()
  } catch (err: unknown) {
    const code = (err as { data?: { code?: string } })?.data?.code ?? ''
    const keyMap: Record<string, string> = {
      AUTH_062: 'parental_consent.error_auth_062',
      AUTH_063: 'parental_consent.error_auth_063',
      AUTH_067: 'parental_consent.error_auth_067',
      AUTH_068: 'parental_consent.error_auth_068',
      AUTH_069: 'parental_consent.error_auth_069',
    }
    notification.error(t(keyMap[code] ?? 'common.error.unknown'))
  } finally {
    sending.value = false
  }
}

async function handleCancelInvitation(linkId: string) {
  try {
    await cancelInvitation(linkId)
    notification.success(t('parental_consent.invite_cancelled'))
    await loadData()
  } catch {
    notification.error(t('common.error.unknown'))
  }
}

onMounted(loadData)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-2">{{ $t('parental_consent.pending_title') }}</h1>
    <p class="text-gray-600 mb-6">{{ $t('parental_consent.pending_description') }}</p>

    <!-- 招待送信フォーム -->
    <div class="bg-white border border-gray-200 rounded-lg p-6 mb-6">
      <h2 class="text-lg font-semibold mb-4">{{ $t('parental_consent.invitations_title') }}</h2>
      <div class="flex gap-2">
        <input
          v-model="parentEmail"
          type="email"
          :placeholder="$t('parental_consent.invite_email_placeholder')"
          class="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <button
          :disabled="sending || !parentEmail"
          class="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 disabled:opacity-50"
          @click="handleSendInvitation"
        >
          {{ $t('parental_consent.send_invite') }}
        </button>
      </div>
    </div>

    <!-- 送信済み招待一覧 -->
    <div v-if="loading" class="text-center py-8 text-gray-400">...</div>
    <div v-else-if="invitations.length === 0" class="text-center py-8 text-gray-400">
      {{ $t('parental_consent.no_invitations') }}
    </div>
    <ul v-else class="space-y-3">
      <li
        v-for="inv in invitations"
        :key="inv.linkId"
        class="bg-white border border-gray-200 rounded-lg p-4 flex items-center justify-between"
      >
        <div>
          <p class="font-medium">{{ inv.parentEmail }}</p>
          <p class="text-sm text-gray-500">
            <span
              :class="{
                'text-yellow-600': inv.status === 'PENDING',
                'text-green-600': inv.status === 'APPROVED',
                'text-red-600': inv.status === 'REJECTED' || inv.status === 'REVOKED',
              }"
            >{{ $t(`parental_consent.status_${inv.status.toLowerCase()}`) }}</span>
            · {{ $t('parental_consent.expires_at', { date: new Date(inv.expiresAt).toLocaleDateString() }) }}
          </p>
        </div>
        <button
          v-if="inv.status === 'PENDING'"
          class="text-sm text-red-600 hover:text-red-800"
          @click="handleCancelInvitation(inv.linkId)"
        >
          {{ $t('parental_consent.cancel_invite') }}
        </button>
      </li>
    </ul>
  </div>
</template>
