<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const api = useApi()
const notification = useNotification()
const loading = ref(false)

const schema = toTypedSchema(
  z.object({
    email: z
      .string()
      .min(1, 'メールアドレスは必須です')
      .email('有効なメールアドレスを入力してください'),
    password: z.string().min(8, 'パスワードは8文字以上で入力してください'),
    lastName: z.string().min(1, '姓は必須です').max(50, '姓は50文字以内で入力してください'),
    firstName: z.string().min(1, '名は必須です').max(50, '名は50文字以内で入力してください'),
    displayName: z
      .string()
      .min(1, '表示名は必須です')
      .max(50, '表示名は50文字以内で入力してください'),
    postalCode: z
      .string()
      .min(1, '郵便番号は必須です')
      .regex(/^\d{3}-?\d{4}$/, '郵便番号の形式が正しくありません（例: 123-4567）'),
  }),
)

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
})

const [email, emailProps] = defineField('email')
const [password, passwordProps] = defineField('password')
const [lastName, lastNameProps] = defineField('lastName')
const [firstName, firstNameProps] = defineField('firstName')
const [displayName, displayNameProps] = defineField('displayName')
const [postalCode, postalCodeProps] = defineField('postalCode')

const submitted = ref(false)

const onSubmit = handleSubmit(async (values) => {
  submitted.value = true
  loading.value = true
  try {
    await api('/api/v1/auth/register', {
      method: 'POST',
      body: {
        email: values.email,
        password: values.password,
        lastName: values.lastName,
        firstName: values.firstName,
        displayName: values.displayName,
        postalCode: values.postalCode,
        locale: 'ja',
        timezone: 'Asia/Tokyo',
      },
    })
    notification.success('登録が完了しました。メールをご確認ください。')
    navigateTo(`/verify-email?email=${encodeURIComponent(values.email)}`)
  } catch (e: unknown) {
    const err = e as { data?: { error?: { message?: string } } }
    const message = err?.data?.error?.message || 'しばらく時間をおいて再度お試しください。'
    notification.error('登録に失敗しました', message)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <form
    @submit.prevent="
      submitted = true
      onSubmit()
    "
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-col gap-2">
        <label for="email">メールアドレス <span class="text-red-500">※</span></label>
        <InputText
          id="email"
          v-model="email"
          v-bind="emailProps"
          type="email"
          placeholder="example@mannschaft.app"
          :invalid="submitted && !!errors.email"
        />
        <small v-if="submitted && errors.email" class="text-red-500">{{ errors.email }}</small>
      </div>

      <div class="flex flex-col gap-2">
        <label for="password">パスワード <span class="text-red-500">※</span></label>
        <Password
          id="password"
          v-model="password"
          v-bind="passwordProps"
          :feedback="true"
          toggle-mask
          fluid
          :invalid="submitted && !!errors.password"
        />
        <small v-if="submitted && errors.password" class="text-red-500">{{
          errors.password
        }}</small>
        <small class="text-surface-400">パスワードは暗号化された状態で保存されます。</small>
      </div>

      <div class="flex flex-col gap-2">
        <label for="postalCode">郵便番号 <span class="text-red-500">※</span></label>
        <InputText
          id="postalCode"
          v-model="postalCode"
          v-bind="postalCodeProps"
          placeholder="123-4567"
          :invalid="submitted && !!errors.postalCode"
        />
        <small v-if="submitted && errors.postalCode" class="text-red-500">{{
          errors.postalCode
        }}</small>
        <small class="text-surface-400">郵便番号は暗号化された状態で保存されます。</small>
      </div>

      <div class="flex gap-4">
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <label for="lastName">姓 <span class="text-red-500">※</span></label>
          <InputText
            id="lastName"
            v-model="lastName"
            v-bind="lastNameProps"
            placeholder="山田"
            :invalid="submitted && !!errors.lastName"
            class="w-full"
          />
          <small v-if="submitted && errors.lastName" class="text-red-500">{{
            errors.lastName
          }}</small>
        </div>
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <label for="firstName">名 <span class="text-red-500">※</span></label>
          <InputText
            id="firstName"
            v-model="firstName"
            v-bind="firstNameProps"
            placeholder="太郎"
            :invalid="submitted && !!errors.firstName"
            class="w-full"
          />
          <small v-if="submitted && errors.firstName" class="text-red-500">{{
            errors.firstName
          }}</small>
        </div>
      </div>

      <div class="flex flex-col gap-2">
        <label for="displayName">表示名 <span class="text-red-500">※</span></label>
        <InputText
          id="displayName"
          v-model="displayName"
          v-bind="displayNameProps"
          placeholder="yamada_taro"
          :invalid="submitted && !!errors.displayName"
        />
        <small v-if="submitted && errors.displayName" class="text-red-500">{{
          errors.displayName
        }}</small>
      </div>

      <Button
        type="submit"
        label="アカウント作成"
        icon="pi pi-user-plus"
        :loading="loading"
        class="mt-2"
      />

      <div class="text-center">
        <NuxtLink to="/login" class="text-sm text-primary hover:underline">
          すでにアカウントをお持ちですか？
        </NuxtLink>
      </div>
    </div>
  </form>
</template>
