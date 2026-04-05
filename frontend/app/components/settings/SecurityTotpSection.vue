<script setup lang="ts">
const notification = useNotification()
const { setup2fa, regenerateBackupCodes } = useAuthApi()

const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
const backupCodes = ref<string[]>([])
const showBackupCodesDialog = ref(false)
const setting2fa = ref(false)
const regenerating = ref(false)

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
</template>
