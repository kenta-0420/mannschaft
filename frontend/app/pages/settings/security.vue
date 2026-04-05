<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const {
  getSessions,
  revokeSession,
  revokeAllSessions,
  getWebAuthnCredentials,
  deleteWebAuthnCredential,
  updateWebAuthnCredential,
} = useAuthApi()

const loading = ref(true)
const sessions = ref<AuthSessionResponse[]>([])
const credentials = ref<WebAuthnCredentialResponse[]>([])

onMounted(async () => {
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [sessRes, credRes] = await Promise.all([getSessions(), getWebAuthnCredentials()])
    sessions.value = sessRes.data
    credentials.value = credRes.data
  } catch {
    notification.error('セキュリティ情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleRevokeSession(id: number) {
  try {
    await revokeSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    notification.success('セッションを無効化しました')
  } catch {
    notification.error('セッションの無効化に失敗しました')
  }
}

async function handleRevokeAllSessions() {
  try {
    await revokeAllSessions()
    sessions.value = []
    notification.success('全デバイスからログアウトしました')
  } catch {
    notification.error('全デバイスログアウトに失敗しました')
  }
}

async function handleDeleteCredential(id: number) {
  try {
    await deleteWebAuthnCredential(id)
    credentials.value = credentials.value.filter((c) => c.id !== id)
    notification.success('セキュリティキーを削除しました')
  } catch {
    notification.error('セキュリティキーの削除に失敗しました')
  }
}

async function handleRenameCredential(id: number, deviceName: string) {
  try {
    const res = await updateWebAuthnCredential(id, { deviceName })
    const idx = credentials.value.findIndex((c) => c.id === id)
    if (idx !== -1) credentials.value[idx] = res.data
    notification.success('デバイス名を更新しました')
  } catch {
    notification.error('デバイス名の更新に失敗しました')
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">セキュリティ</h1>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <SettingsSecurityTotpSection />

      <SettingsSecuritySessionList
        :sessions="sessions"
        @revoke="handleRevokeSession"
        @revoke-all="handleRevokeAllSessions"
      />

      <SettingsSecurityWebAuthnSection
        :credentials="credentials"
        @delete="handleDeleteCredential"
        @rename="handleRenameCredential"
      />
    </div>
  </div>
</template>
