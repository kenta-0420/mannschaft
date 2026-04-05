<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { getProfile, changeEmail } = useUserSettingsApi()

const loading = ref(true)
const submitting = ref(false)
const currentEmail = ref('')
const sent = ref(false)

const form = ref({
  newEmail: '',
  currentPassword: '',
})

onMounted(async () => {
  try {
    const res = await getProfile()
    currentEmail.value = res.data.email
  } catch {
    notification.error('プロフィール情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
})

const canSubmit = computed(() => {
  return (
    form.value.newEmail && form.value.currentPassword && form.value.newEmail !== currentEmail.value
  )
})

async function handleSubmit() {
  submitting.value = true
  try {
    await changeEmail({
      newEmail: form.value.newEmail,
      currentPassword: form.value.currentPassword,
    })
    sent.value = true
    notification.success('確認メールを送信しました')
  } catch {
    notification.error('メールアドレスの変更リクエストに失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">メールアドレス変更</h1>
    </div>

    <PageLoading v-if="loading" />

    <div
      v-else
      class="fade-in rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
    >
      <template v-if="!sent">
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">現在のメールアドレス</label>
            <InputText :model-value="currentEmail" class="w-full" disabled />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">新しいメールアドレス</label>
            <InputText
              v-model="form.newEmail"
              type="email"
              class="w-full"
              placeholder="new@example.com"
            />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
            <Password
              v-model="form.currentPassword"
              :feedback="false"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>

          <div class="flex justify-end">
            <Button
              label="確認メールを送信"
              icon="pi pi-envelope"
              :loading="submitting"
              :disabled="!canSubmit"
              @click="handleSubmit"
            />
          </div>
        </div>
      </template>

      <template v-else>
        <div class="py-8 text-center">
          <i class="pi pi-check-circle mb-4 text-5xl text-green-500" />
          <h2 class="mb-2 text-lg font-semibold">確認メールを送信しました</h2>
          <p class="text-sm text-surface-500">
            {{ form.newEmail }}
            に確認メールを送信しました。メール内のリンクをクリックして変更を完了してください。
          </p>
        </div>
      </template>
    </div>
  </div>
</template>
