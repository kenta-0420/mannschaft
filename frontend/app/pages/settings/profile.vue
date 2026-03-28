<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()

const loading = ref(true)
const saving = ref(false)
const changingPassword = ref(false)

const profile = ref({
  displayName: '',
  email: '',
  phoneNumber: '',
  avatarUrl: null as string | null,
})

const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const showDeleteDialog = ref(false)

onMounted(async () => {
  try {
    const res = await api<{ data: typeof profile.value }>('/api/v1/users/me')
    profile.value = res.data
  } catch {
    notification.error('プロフィール情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
})

async function saveProfile() {
  saving.value = true
  try {
    await api('/api/v1/users/me', {
      method: 'PATCH',
      body: {
        displayName: profile.value.displayName,
        phoneNumber: profile.value.phoneNumber,
      },
    })
    notification.success('プロフィールを更新しました')
  } catch {
    notification.error('プロフィールの更新に失敗しました')
  } finally {
    saving.value = false
  }
}

async function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  if (file.size > 5 * 1024 * 1024) {
    notification.error('ファイルサイズは5MB以下にしてください')
    return
  }

  const formData = new FormData()
  formData.append('file', file)

  try {
    const res = await api<{ data: { avatarUrl: string } }>('/api/v1/users/me/avatar', {
      method: 'POST',
      body: formData,
    })
    profile.value.avatarUrl = res.data.avatarUrl
    notification.success('アバターを更新しました')
  } catch {
    notification.error('アバターのアップロードに失敗しました')
  }
}

const passwordError = computed(() => {
  if (passwordForm.value.newPassword && passwordForm.value.newPassword.length < 8) {
    return 'パスワードは8文字以上で入力してください'
  }
  if (
    passwordForm.value.confirmPassword &&
    passwordForm.value.newPassword !== passwordForm.value.confirmPassword
  ) {
    return 'パスワードが一致しません'
  }
  return null
})

const canSubmitPassword = computed(() => {
  return (
    passwordForm.value.currentPassword &&
    passwordForm.value.newPassword.length >= 8 &&
    passwordForm.value.newPassword === passwordForm.value.confirmPassword
  )
})

async function changePassword() {
  changingPassword.value = true
  try {
    await api('/api/v1/auth/change-password', {
      method: 'POST',
      body: {
        currentPassword: passwordForm.value.currentPassword,
        newPassword: passwordForm.value.newPassword,
      },
    })
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    notification.success('パスワードを変更しました')
  } catch {
    notification.error('パスワードの変更に失敗しました。現在のパスワードを確認してください')
  } finally {
    changingPassword.value = false
  }
}

async function deleteAccount() {
  try {
    await api('/api/v1/users/me', { method: 'DELETE' })
    authStore.logout()
    navigateTo('/login')
  } catch {
    notification.error('アカウントの削除に失敗しました')
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <h1 class="mb-6 text-2xl font-bold">プロフィール設定</h1>

    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else class="space-y-8">
      <!-- プロフィール情報 -->
      <div
        class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800"
      >
        <h2 class="mb-4 text-lg font-semibold">プロフィール情報</h2>
        <div class="space-y-4">
          <div class="flex items-center gap-4">
            <div class="relative">
              <img
                v-if="profile.avatarUrl"
                :src="profile.avatarUrl"
                alt="アバター"
                class="h-20 w-20 rounded-full object-cover"
              />
              <div
                v-else
                class="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 text-2xl text-primary"
              >
                <i class="pi pi-user" />
              </div>
            </div>
            <div>
              <label class="cursor-pointer">
                <input type="file" accept="image/*" class="hidden" @change="uploadAvatar" />
                <Button
                  label="画像を変更"
                  icon="pi pi-upload"
                  severity="secondary"
                  size="small"
                  as="span"
                />
              </label>
              <p class="mt-1 text-xs text-surface-500">5MB以下のJPG, PNG</p>
            </div>
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">表示名</label>
            <InputText v-model="profile.displayName" class="w-full" />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">メールアドレス</label>
            <InputText :model-value="profile.email" class="w-full" disabled />
            <p class="mt-1 text-xs text-surface-500">
              メールアドレスの変更はサポートにお問い合わせください
            </p>
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">電話番号</label>
            <InputText v-model="profile.phoneNumber" class="w-full" placeholder="090-0000-0000" />
          </div>

          <div class="flex justify-end">
            <Button label="保存" icon="pi pi-check" :loading="saving" @click="saveProfile" />
          </div>
        </div>
      </div>

      <!-- パスワード変更 -->
      <div
        class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800"
      >
        <h2 class="mb-4 text-lg font-semibold">パスワード変更</h2>
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
            <Password
              v-model="passwordForm.currentPassword"
              :feedback="false"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">新しいパスワード</label>
            <Password
              v-model="passwordForm.newPassword"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">新しいパスワード（確認）</label>
            <Password
              v-model="passwordForm.confirmPassword"
              :feedback="false"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <p v-if="passwordError" class="text-sm text-red-500">{{ passwordError }}</p>
          <div class="flex justify-end">
            <Button
              label="パスワードを変更"
              icon="pi pi-lock"
              :loading="changingPassword"
              :disabled="!canSubmitPassword"
              @click="changePassword"
            />
          </div>
        </div>
      </div>

      <!-- アカウント削除 -->
      <div
        class="rounded-xl border border-red-200 bg-surface-0 p-6 dark:border-red-900 dark:bg-surface-800"
      >
        <h2 class="mb-2 text-lg font-semibold text-red-600">アカウント削除</h2>
        <p class="mb-4 text-sm text-surface-500">
          アカウントを削除すると、全てのデータが完全に削除されます。この操作は取り消せません。
        </p>
        <Button
          label="アカウントを削除"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="showDeleteDialog = true"
        />
      </div>

      <Dialog
        v-model:visible="showDeleteDialog"
        header="アカウント削除の確認"
        :modal="true"
        class="w-full max-w-md"
      >
        <p class="mb-4">
          本当にアカウントを削除しますか？全てのデータが完全に削除され、復元できません。
        </p>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showDeleteDialog = false" />
          <Button label="削除する" severity="danger" @click="deleteAccount" />
        </div>
      </Dialog>
    </div>
  </div>
</template>
