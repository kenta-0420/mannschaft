<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { getProfile, changePassword, setupPassword } = useUserSettingsApi()

const loading = ref(true)
const submitting = ref(false)
const hasPassword = ref(true)

const form = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

onMounted(async () => {
  try {
    const res = await getProfile()
    hasPassword.value = res.data.hasPassword
  } catch {
    notification.error('プロフィール情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
})

const passwordError = computed(() => {
  if (form.value.newPassword && form.value.newPassword.length < 8) {
    return 'パスワードは8文字以上で入力してください'
  }
  if (form.value.confirmPassword && form.value.newPassword !== form.value.confirmPassword) {
    return 'パスワードが一致しません'
  }
  return null
})

const canSubmit = computed(() => {
  if (hasPassword.value) {
    return (
      form.value.currentPassword &&
      form.value.newPassword.length >= 8 &&
      form.value.newPassword === form.value.confirmPassword
    )
  }
  return form.value.newPassword.length >= 8 && form.value.newPassword === form.value.confirmPassword
})

async function handleSubmit() {
  submitting.value = true
  try {
    if (hasPassword.value) {
      await changePassword({
        currentPassword: form.value.currentPassword,
        newPassword: form.value.newPassword,
      })
    } else {
      await setupPassword(form.value.newPassword)
      hasPassword.value = true
    }
    form.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    notification.success(
      hasPassword.value ? 'パスワードを変更しました' : 'パスワードを設定しました',
    )
  } catch {
    notification.error(
      hasPassword.value
        ? 'パスワードの変更に失敗しました。現在のパスワードを確認してください'
        : 'パスワードの設定に失敗しました',
    )
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <BackButton to="/settings" />
    <PageHeader :title="hasPassword ? 'パスワード変更' : 'パスワード設定'" />

    <PageLoading v-if="loading" />

    <SectionCard v-else class="fade-in">
      <div class="space-y-4">
        <div v-if="hasPassword">
          <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
          <Password
            v-model="form.currentPassword"
            :feedback="false"
            toggle-mask
            class="w-full"
            input-class="w-full"
          />
        </div>

        <div v-if="!hasPassword">
          <p class="mb-4 text-sm text-surface-500">
            現在パスワードが設定されていません。OAuth認証でログインしている場合、パスワードを設定することでメール・パスワードでもログインできるようになります。
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">新しいパスワード</label>
          <Password v-model="form.newPassword" toggle-mask class="w-full" input-class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">新しいパスワード（確認）</label>
          <Password
            v-model="form.confirmPassword"
            :feedback="false"
            toggle-mask
            class="w-full"
            input-class="w-full"
          />
        </div>

        <p v-if="passwordError" class="text-sm text-red-500">{{ passwordError }}</p>

        <div class="flex justify-end">
          <Button
            :label="hasPassword ? 'パスワードを変更' : 'パスワードを設定'"
            icon="pi pi-lock"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          />
        </div>
      </div>
    </SectionCard>
  </div>
</template>
