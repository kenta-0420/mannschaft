<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const { t } = useI18n()
const { resolveMessage } = useErrorHandler()

const route = useRoute()
const token = route.query.token as string | undefined

if (!token) {
  navigateTo('/forgot-password')
}

// ポリシー: utils/passwordPolicy.ts の meetsPasswordPolicy/countCharTypes を共用（auto-import）
const schema = toTypedSchema(
  z
    .object({
      newPassword: z
        .string()
        .min(8, () => t('settings.password.length_error'))
        .refine((val) => countCharTypes(val) >= 3, () => t('settings.password.policy_violation')),
      confirmPassword: z.string().min(1, () => t('settings.password.mismatch_error')),
    })
    .refine((data) => data.newPassword === data.confirmPassword, {
      message: () => t('settings.password.mismatch_error'),
      path: ['confirmPassword'],
    }),
)

const { defineField, handleSubmit, errors } = useForm({ validationSchema: schema })
const [newPassword, newPasswordProps] = defineField('newPassword')
const [confirmPassword, confirmPasswordProps] = defineField('confirmPassword')

const loading = ref(false)
const success = ref(false)
const errorMessage = ref('')

const api = useApi()
const notification = useNotification()

const onSubmit = handleSubmit(async (values) => {
  loading.value = true
  errorMessage.value = ''
  try {
    await api('/api/v1/auth/password-reset/confirm', {
      method: 'POST',
      body: { token, newPassword: values.newPassword },
    })
    success.value = true
    notification.success(t('auth.password_reset.success_message'))
  } catch (e) {
    // error.code を判別して resolveMessage で解決。
    // AUTH_015: トークン無効/期限切れ、AUTH_008: ポリシー違反
    const code = (e as { data?: { error?: { code?: string; message?: string } } })?.data?.error
      ?.code
    if (code) {
      errorMessage.value = resolveMessage(
        code,
        (e as { data?: { error?: { message?: string } } })?.data?.error?.message,
      )
    } else {
      errorMessage.value = t('error.unknown')
    }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="!success">
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('auth.password_reset.confirm_description') }}
    </p>
    <form @submit.prevent="onSubmit">
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label for="newPassword">{{ $t('auth.password_reset.new_password_label') }}</label>
          <Password
            v-model="newPassword"
            input-id="newPassword"
            v-bind="newPasswordProps"
            :feedback="true"
            toggle-mask
            fluid
            :invalid="!!errors.newPassword"
          />
          <p class="mt-1 text-xs text-surface-500">{{ $t('settings.password.policy_hint') }}</p>
          <small v-if="errors.newPassword" class="text-red-500">{{ errors.newPassword }}</small>
        </div>
        <div class="flex flex-col gap-2">
          <label for="confirmPassword">{{ $t('auth.password_reset.confirm_password_label') }}</label>
          <Password
            v-model="confirmPassword"
            input-id="confirmPassword"
            v-bind="confirmPasswordProps"
            :feedback="false"
            toggle-mask
            fluid
            :invalid="!!errors.confirmPassword"
          />
          <small v-if="errors.confirmPassword" class="text-red-500">{{ errors.confirmPassword }}</small>
        </div>
        <Message v-if="errorMessage" severity="error" :closable="false">
          {{ errorMessage }}
        </Message>
        <Button
          type="submit"
          :label="$t('auth.password_reset.submit_button')"
          icon="pi pi-lock"
          :loading="loading"
          class="mt-2"
        />
        <div class="text-center">
          <NuxtLink to="/login" class="text-sm text-primary hover:underline">
            {{ $t('auth.password_reset.back_to_login') }}
          </NuxtLink>
        </div>
      </div>
    </form>
  </div>
  <div v-else class="flex flex-col items-center gap-4 text-center">
    <i class="pi pi-check-circle text-4xl text-green-500" />
    <p>{{ $t('auth.password_reset.success_message') }}</p>
    <NuxtLink to="/login">
      <Button :label="$t('auth.password_reset.success_login_button')" icon="pi pi-sign-in" />
    </NuxtLink>
  </div>
</template>
