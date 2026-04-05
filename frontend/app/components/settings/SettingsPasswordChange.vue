<script setup lang="ts">
const api = useApi()
const notification = useNotification()

const changingPassword = ref(false)

const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

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
</script>

<template>
  <SectionCard title="パスワード変更">
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
  </SectionCard>
</template>
