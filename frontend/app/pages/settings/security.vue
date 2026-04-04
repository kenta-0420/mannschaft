<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const {
  setup2fa,
  regenerateBackupCodes,
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

// 2FA state
const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
const backupCodes = ref<string[]>([])
const showBackupCodesDialog = ref(false)
const setting2fa = ref(false)
const regenerating = ref(false)

// WebAuthn rename
const renameDialog = ref(false)
const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
const newDeviceName = ref('')

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

// === 2FA ===
async function handleSetup2fa() {
  setting2fa.value = true
  try {
    const res = await setup2fa()
    totpSetup.value = res.data
  } catch {
    notification.error('2FAセットアップの開始に失敗しました')
  } finally {
    setting2fa.value = false
  }
}

async function handleRegenerateBackupCodes() {
  regenerating.value = true
  try {
    const res = await regenerateBackupCodes()
    backupCodes.value = res.data.backupCodes
    showBackupCodesDialog.value = true
    notification.success('バックアップコードを再生成しました')
  } catch {
    notification.error('バックアップコードの再生成に失敗しました')
  } finally {
    regenerating.value = false
  }
}

// === Sessions ===
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

// === WebAuthn ===
async function handleDeleteCredential(id: number) {
  try {
    await deleteWebAuthnCredential(id)
    credentials.value = credentials.value.filter((c) => c.id !== id)
    notification.success('セキュリティキーを削除しました')
  } catch {
    notification.error('セキュリティキーの削除に失敗しました')
  }
}

function openRenameDialog(cred: WebAuthnCredentialResponse) {
  renameTarget.value = cred
  newDeviceName.value = cred.deviceName
  renameDialog.value = true
}

async function handleRenameCredential() {
  if (!renameTarget.value) return
  try {
    const res = await updateWebAuthnCredential(renameTarget.value.id, {
      deviceName: newDeviceName.value,
    })
    const idx = credentials.value.findIndex((c) => c.id === renameTarget.value!.id)
    if (idx !== -1) credentials.value[idx] = res.data
    renameDialog.value = false
    notification.success('デバイス名を更新しました')
  } catch {
    notification.error('デバイス名の更新に失敗しました')
  }
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
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
      <!-- 2FA設定 -->
      <SectionCard title="二要素認証（2FA）">
        <div v-if="!totpSetup" class="space-y-4">
          <p class="text-sm text-surface-500">
            認証アプリ（Google Authenticatorなど）を使用して、アカウントのセキュリティを強化します。
          </p>
          <div class="flex flex-wrap gap-2">
            <Button
              label="2FAをセットアップ"
              icon="pi pi-shield"
              :loading="setting2fa"
              @click="handleSetup2fa"
            />
            <Button
              label="バックアップコード再生成"
              icon="pi pi-refresh"
              severity="secondary"
              :loading="regenerating"
              @click="handleRegenerateBackupCodes"
            />
          </div>
        </div>

        <div v-else class="space-y-4">
          <p class="text-sm text-surface-500">認証アプリでQRコードをスキャンしてください。</p>
          <div class="flex justify-center">
            <img :src="totpSetup.qrCodeUrl" alt="TOTP QRコード" class="h-48 w-48" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">シークレットキー</label>
            <code class="block rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-700">{{
              totpSetup.secret
            }}</code>
          </div>
        </div>
      </SectionCard>

      <!-- アクティブセッション -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">アクティブセッション</h2>
          <Button
            v-if="sessions.length > 0"
            label="全てログアウト"
            icon="pi pi-sign-out"
            severity="danger"
            text
            size="small"
            @click="handleRevokeAllSessions"
          />
        </div>

        <div v-if="sessions.length === 0" class="py-4 text-center text-surface-400">
          セッション情報がありません
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
          >
            <div>
              <p class="text-sm font-medium">
                {{ session.userAgent || '不明なデバイス' }}
                <Tag v-if="session.isCurrent" value="現在" severity="success" class="ml-2" />
              </p>
              <p class="text-xs text-surface-500">
                IP: {{ session.ipAddress || '-' }} / {{ formatDate(session.createdAt) }}
              </p>
            </div>
            <Button
              v-if="!session.isCurrent"
              icon="pi pi-times"
              severity="danger"
              text
              rounded
              size="small"
              @click="handleRevokeSession(session.id)"
            />
          </div>
        </div>
      </SectionCard>

      <!-- WebAuthn -->
      <SectionCard title="セキュリティキー（WebAuthn）">
        <p class="mb-4 text-sm text-surface-500">
          FIDO2/WebAuthn対応のセキュリティキーや生体認証を登録できます。
        </p>

        <div v-if="credentials.length === 0" class="py-4 text-center text-surface-400">
          登録されたセキュリティキーはありません
        </div>
        <div v-else class="mb-4 space-y-3">
          <div
            v-for="cred in credentials"
            :key="cred.id"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
          >
            <div>
              <p class="text-sm font-medium">
                <i class="pi pi-key mr-1" />
                {{ cred.deviceName || 'セキュリティキー' }}
              </p>
              <p class="text-xs text-surface-500">
                最終使用: {{ formatDate(cred.lastUsedAt) }} / 登録: {{ formatDate(cred.createdAt) }}
              </p>
            </div>
            <div class="flex gap-1">
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                text
                rounded
                size="small"
                @click="openRenameDialog(cred)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                rounded
                size="small"
                @click="handleDeleteCredential(cred.id)"
              />
            </div>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- バックアップコードダイアログ -->
    <Dialog
      v-model:visible="showBackupCodesDialog"
      header="バックアップコード"
      :modal="true"
      class="w-full max-w-md"
    >
      <p class="mb-4 text-sm text-surface-500">
        以下のバックアップコードを安全な場所に保管してください。各コードは一度だけ使用できます。
      </p>
      <div class="grid grid-cols-2 gap-2">
        <code
          v-for="code in backupCodes"
          :key="code"
          class="rounded bg-surface-100 px-3 py-2 text-center text-sm dark:bg-surface-700"
        >
          {{ code }}
        </code>
      </div>
      <div class="mt-4 flex justify-end">
        <Button label="閉じる" @click="showBackupCodesDialog = false" />
      </div>
    </Dialog>

    <!-- デバイス名変更ダイアログ -->
    <Dialog
      v-model:visible="renameDialog"
      header="デバイス名の変更"
      :modal="true"
      class="w-full max-w-sm"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">デバイス名</label>
          <InputText v-model="newDeviceName" class="w-full" />
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="renameDialog = false" />
          <Button label="保存" @click="handleRenameCredential" />
        </div>
      </div>
    </Dialog>
  </div>
</template>
