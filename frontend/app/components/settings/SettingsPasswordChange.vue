<script setup lang="ts">
const api = useApi()
const notification = useNotification()
const { t } = useI18n()

const changingPassword = ref(false)

const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const passwordError = computed(() => {
  if (passwordForm.value.newPassword && passwordForm.value.newPassword.length < 8) {
    return t('validation.min_length', { min: 8 })
  }
  if (
    passwordForm.value.confirmPassword &&
    passwordForm.value.newPassword !== passwordForm.value.confirmPassword
  ) {
    return t('validation.password_mismatch')
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
    notification.success(t('settings.settings.password.change_success'))
  } catch {
    notification.error(t('settings.settings.password.change_error'))
  } finally {
    changingPassword.value = false
  }
}
</script>

<template>
  <SectionCard :title="$t('settings.settings.password.section_title_change')">
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.password.current_password') }}</label>
        <Password
          v-model="passwordForm.currentPassword"
          :feedback="false"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.password.new_password') }}</label>
        <Password
          v-model="passwordForm.newPassword"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.password.confirm_password') }}</label>
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
          translate="no"
          :label="$t('settings.settings.password.change_button')"
          icon="pi pi-lock"
          :loading="changingPassword"
          :disabled="!canSubmitPassword"
          @click="changePassword"
        />
      </div>
    </div>
  </SectionCard>
</template>
