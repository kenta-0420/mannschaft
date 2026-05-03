<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'

defineProps<{
  totpSetup: { secret: string; qrCodeUrl: string } | null
  setting2fa: boolean
  regenerating: boolean
  sessions: AuthSessionResponse[]
  credentials: WebAuthnCredentialResponse[]
  backupCodes: string[]
  showBackupCodesDialog: boolean
  renameDialog: boolean
  newDeviceName: string
}>()

defineEmits<{
  setup2fa: []
  regenerateBackupCodes: []
  revokeSession: [id: number]
  revokeAllSessions: []
  deleteCredential: [id: number]
  openRenameDialog: [cred: WebAuthnCredentialResponse]
  renameCredential: []
  'update:showBackupCodesDialog': [value: boolean]
  'update:renameDialog': [value: boolean]
  'update:newDeviceName': [value: string]
}>()

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
</script>

<template>
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
          @click="$emit('setup2fa')"
        />
        <Button
          label="バックアップコード再生成"
          icon="pi pi-refresh"
          severity="secondary"
          :loading="regenerating"
          @click="$emit('regenerateBackupCodes')"
        />
      </div>
    </div>
    <div v-else class="space-y-4">
      <p class="text-sm text-surface-500">認証アプリでQRコードをスキャンしてください。</p>
      <div class="flex justify-center">
        <img :src="totpSetup.qrCodeUrl" alt="TOTP QRコード" class="h-48 w-48" >
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">シークレットキー</label>
        <code class="block rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-700">{{
          totpSetup.secret
        }}</code>
      </div>
    </div>
  </SectionCard>

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
        @click="$emit('revokeAllSessions')"
      />
    </div>
    <div v-if="sessions.length === 0" class="py-4 text-center text-surface-400">
      セッション情報がありません
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
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
          @click="$emit('revokeSession', session.id)"
        />
      </div>
    </div>
  </SectionCard>

  <SectionCard title="セキュリティキー（WebAuthn）">
    <p class="mb-4 text-sm text-surface-500">
      FIDO2/WebAuthn対応のセキュリティキーや生体認証を登録できます。
    </p>
    <div v-if="credentials.length === 0" class="py-4 text-center text-surface-400">
      登録されたセキュリティキーはありません
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="cred in credentials"
        :key="cred.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">
            <i class="pi pi-key mr-1" />{{ cred.deviceName || 'セキュリティキー' }}
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
            @click="$emit('openRenameDialog', cred)"
          />
          <Button
            icon="pi pi-trash"
            severity="danger"
            text
            rounded
            size="small"
            @click="$emit('deleteCredential', cred.id)"
          />
        </div>
      </div>
    </div>
  </SectionCard>

  <Dialog
    :visible="showBackupCodesDialog"
    header="バックアップコード"
    :modal="true"
    class="w-full max-w-md"
    @update:visible="$emit('update:showBackupCodesDialog', $event)"
  >
    <p class="mb-4 text-sm text-surface-500">
      以下のバックアップコードを安全な場所に保管してください。各コードは一度だけ使用できます。
    </p>
    <div class="grid grid-cols-2 gap-2">
      <code
        v-for="code in backupCodes"
        :key="code"
        class="rounded bg-surface-100 px-3 py-2 text-center text-sm dark:bg-surface-700"
        >{{ code }}</code
      >
    </div>
    <div class="mt-4 flex justify-end">
      <Button label="閉じる" @click="$emit('update:showBackupCodesDialog', false)" />
    </div>
  </Dialog>

  <Dialog
    :visible="renameDialog"
    header="デバイス名の変更"
    :modal="true"
    class="w-full max-w-sm"
    @update:visible="$emit('update:renameDialog', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">デバイス名</label>
        <InputText :model-value="newDeviceName" class="w-full" @update:model-value="$emit('update:newDeviceName', $event as string)" />
      </div>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="$emit('update:renameDialog', false)" />
        <Button label="保存" @click="$emit('renameCredential')" />
      </div>
    </div>
  </Dialog>
</template>
