<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'

const props = withDefaults(
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
    // パスキー登録 UI（オプション: security.vue から利用。account.vue 等では省略可能）
    isRegisteringPasskey?: boolean
    registerDialog?: boolean
    newPasskeyName?: string
  }>(),
  {
    isRegisteringPasskey: false,
    registerDialog: false,
    newPasskeyName: '',
  },
)

defineEmits<{
  setup2fa: []
  regenerateBackupCodes: []
  revokeSession: [id: number]
  revokeAllSessions: []
  deleteCredential: [id: number]
  openRenameDialog: [cred: WebAuthnCredentialResponse]
  renameCredential: []
  passkeyRegister: []
  'update:showBackupCodesDialog': [value: boolean]
  'update:renameDialog': [value: boolean]
  'update:newDeviceName': [value: string]
  'update:registerDialog': [value: boolean]
  'update:newPasskeyName': [value: string]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

// WebAuthn サポート検出（クライアントサイドのみ）
const isWebAuthnSupported = ref(false)
onMounted(() => {
  isWebAuthnSupported.value = typeof window !== 'undefined' && !!window.PublicKeyCredential
})

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
}

/**
 * セッションの User-Agent を「Windows の Chrome」のような読みやすいデバイス名に整形する。
 * - ブラウザ・OS 両方判明 → i18n テンプレートで結合
 * - 片方のみ判明 → そのまま表示（ブラウザ優先）
 * - 両方とも不明 → 元の userAgent 文字列（それも無ければ「不明なデバイス」相当）
 */
function deviceLabel(userAgent: string | null): string {
  const { browser, os } = parseUserAgent(userAgent)
  if (browser && os) {
    return t('settings.security.session.device_label', { os, browser })
  }
  if (browser) return browser
  if (os) return os
  return userAgent || t('settings.security.session.unknown_device')
}
</script>

<template>
  <SectionCard :title="$t('settings.security.two_fa_section_title')">
    <div v-if="!totpSetup" class="space-y-4">
      <p class="text-sm text-surface-500">
        {{ $t('settings.security.two_fa_description') }}
      </p>
      <div class="flex flex-wrap gap-2">
        <Button
          translate="no"
          :label="$t('settings.security.setup_2fa_button')"
          icon="pi pi-shield"
          :loading="setting2fa"
          @click="$emit('setup2fa')"
        />
        <Button
          translate="no"
          :label="$t('settings.security.regenerate_backup_codes_button')"
          icon="pi pi-refresh"
          severity="secondary"
          :loading="regenerating"
          @click="$emit('regenerateBackupCodes')"
        />
      </div>
    </div>
    <div v-else class="space-y-4">
      <p class="text-sm text-surface-500">{{ $t('settings.security.scan_qr_description') }}</p>
      <div class="flex justify-center">
        <img :src="totpSetup.qrCodeUrl" :alt="$t('settings.security.totp_qr_alt')" class="h-48 w-48" >
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.security.secret_key') }}</label>
        <code class="block rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-700">{{
          totpSetup.secret
        }}</code>
      </div>
    </div>
  </SectionCard>

  <SectionCard>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ $t('settings.security.sessions_section_title') }}</h2>
      <Button
        v-if="sessions.length > 0"
        translate="no"
        :label="$t('settings.security.logout_all_button')"
        icon="pi pi-sign-out"
        severity="danger"
        text
        size="small"
        @click="$emit('revokeAllSessions')"
      />
    </div>
    <div v-if="sessions.length === 0" class="py-4 text-center text-surface-400">
      {{ $t('settings.security.no_sessions') }}
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">
            {{ deviceLabel(session.userAgent) }}
            <Tag v-if="session.isCurrent" :value="$t('settings.security.current_session_tag')" severity="success" class="ml-2" />
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

  <SectionCard :title="$t('settings.security.webauthn_section_title')">
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('settings.security.webauthn_description') }}
    </p>
    <div v-if="isWebAuthnSupported" class="mb-4">
      <Button
        translate="no"
        :label="$t('settings.security.webauthn.register_button')"
        icon="pi pi-key"
        :loading="isRegisteringPasskey"
        @click="$emit('update:registerDialog', true)"
      />
    </div>
    <div v-if="credentials.length === 0" class="py-4 text-center text-surface-400">
      {{ $t('settings.security.no_credentials') }}
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="cred in credentials"
        :key="cred.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">
            <i class="pi pi-key mr-1" />{{ cred.deviceName || $t('settings.security.default_credential_name') }}
          </p>
          <p class="text-xs text-surface-500">
            {{ $t('settings.security.last_used', { date: formatDate(cred.lastUsedAt) }) }} / {{ $t('settings.security.registered', { date: formatDate(cred.createdAt) }) }}
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
    :header="$t('settings.security.backup_codes_dialog_title')"
    :modal="true"
    class="w-full max-w-md"
    @update:visible="$emit('update:showBackupCodesDialog', $event)"
  >
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('settings.security.backup_codes_description') }}
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
      <Button translate="no" :label="$t('button.close')" @click="$emit('update:showBackupCodesDialog', false)" />
    </div>
  </Dialog>

  <Dialog
    :visible="renameDialog"
    :header="$t('settings.security.rename_dialog_title')"
    :modal="true"
    class="w-full max-w-sm"
    @update:visible="$emit('update:renameDialog', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.security.device_name_label') }}</label>
        <InputText :model-value="newDeviceName" class="w-full" @update:model-value="$emit('update:newDeviceName', $event as string)" />
      </div>
      <div class="flex justify-end gap-2">
        <Button translate="no" :label="$t('button.cancel')" severity="secondary" @click="$emit('update:renameDialog', false)" />
        <Button translate="no" :label="$t('button.save')" @click="$emit('renameCredential')" />
      </div>
    </div>
  </Dialog>

  <Dialog
    :visible="registerDialog"
    :header="$t('settings.security.webauthn.register_dialog_title')"
    :modal="true"
    class="w-full max-w-sm"
    @update:visible="$emit('update:registerDialog', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.security.webauthn.device_name_label') }}</label>
        <InputText
          :model-value="newPasskeyName"
          :placeholder="$t('settings.security.webauthn.device_name_placeholder')"
          class="w-full"
          @update:model-value="$emit('update:newPasskeyName', $event as string)"
        />
      </div>
      <div class="flex justify-end gap-2">
        <Button
          translate="no"
          :label="$t('settings.security.webauthn.register_cancel')"
          severity="secondary"
          @click="$emit('update:registerDialog', false)"
        />
        <Button
          translate="no"
          :label="$t('settings.security.webauthn.register_confirm')"
          :loading="isRegisteringPasskey"
          @click="$emit('passkeyRegister')"
        />
      </div>
    </div>
  </Dialog>
</template>
