<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const route = useRoute()
const token = route.query.token as string | undefined

if (!token) {
  navigateTo('/forgot-password')
}

const schema = toTypedSchema(z.object({
  newPassword: z.string().min(8, 'パスワードは8文字以上で入力してください'),
  confirmPassword: z.string().min(1, '確認用パスワードは必須です'),
}).refine(data => data.newPassword === data.confirmPassword, {
  message: 'パスワードが一致しません',
  path: ['confirmPassword'],
}))

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
    notification.success('パスワードが変更されました')
  }
  catch {
    errorMessage.value = 'パスワードのリセットに失敗しました。リンクの有効期限が切れている可能性があります。'
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="!success">
    <p class="mb-4 text-sm text-surface-500">
      新しいパスワードを入力してください。
    </p>
    <form @submit.prevent="onSubmit">
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label for="newPassword">新しいパスワード</label>
          <Password
            v-model="newPassword"
            input-id="newPassword"
            v-bind="newPasswordProps"
            :feedback="true"
            toggle-mask
            fluid
            :invalid="!!errors.newPassword"
          />
          <small v-if="errors.newPassword" class="text-red-500">{{ errors.newPassword }}</small>
        </div>
        <div class="flex flex-col gap-2">
          <label for="confirmPassword">確認用パスワード</label>
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
          label="パスワードを変更"
          icon="pi pi-lock"
          :loading="loading"
          class="mt-2"
        />
        <div class="text-center">
          <NuxtLink to="/login" class="text-sm text-primary hover:underline">
            ログインに戻る
          </NuxtLink>
        </div>
      </div>
    </form>
  </div>
  <div v-else class="flex flex-col items-center gap-4 text-center">
    <i class="pi pi-check-circle text-4xl text-green-500" />
    <p>パスワードが変更されました。</p>
    <NuxtLink to="/login">
      <Button label="ログインへ" icon="pi pi-sign-in" />
    </NuxtLink>
  </div>
</template>
