<script setup lang="ts">
import type { WebAuthnCredentialResponse } from '~/types/auth'

const props = defineProps<{
  credentials: WebAuthnCredentialResponse[]
}>()

const emit = defineEmits<{
  delete: [id: number]
  rename: [id: number, deviceName: string]
}>()

const renameDialog = ref(false)
const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
const newDeviceName = ref('')

function openRenameDialog(cred: WebAuthnCredentialResponse) {
  renameTarget.value = cred
  newDeviceName.value = cred.deviceName
  renameDialog.value = true
}

function handleRename() {
  if (!renameTarget.value) return
  emit('rename', renameTarget.value.id, newDeviceName.value)
  renameDialog.value = false
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
</script>

<template>
  <SectionCard title="セキュリティキー（WebAuthn）">
    <p class="mb-4 text-sm text-surface-500">
      FIDO2/WebAuthn対応のセキュリティキーや生体認証を登録できます。
    </p>

    <div v-if="props.credentials.length === 0" class="py-4 text-center text-surface-400">
      登録されたセキュリティキーはありません
    </div>
    <div v-else class="mb-4 space-y-3">
      <div
        v-for="cred in props.credentials"
        :key="cred.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
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
            @click="emit('delete', cred.id)"
          />
        </div>
      </div>
    </div>
  </SectionCard>

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
        <Button label="保存" @click="handleRename" />
      </div>
    </div>
  </Dialog>
</template>
