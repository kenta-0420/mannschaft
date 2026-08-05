<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const schema = toTypedSchema(z.object({
  email: z.string().min(1, 'メールアドレスは必須です').email('有効なメールアドレスを入力してください'),
}))

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
  // ハイドレーション前に入力された値（パスワードマネージャの自動入力を含む）を取り込む。
  // 空文字のままだとハイドレーション時に上書きされて消える。必ずセットアップ時に読むこと。
  initialValues: { email: readPrefilledInputValue('email') },
})
const [email, emailProps] = defineField('email')

const loading = ref(false)
const sent = ref(false)

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()
// ハイドレーション待ちの間もボタンをローディング表示にする（無反応に見える問題の解消）。
// :disabled="!hydrated" は Enter キーによる implicit submission 抑止のため別途維持する
// （PrimeVue の loading は内部的に disabled 相当になるが、明示指定で確実に塞ぐ）。
const submitting = computed(() => loading.value || !hydrated.value)

const api = useApi()

const onSubmit = handleSubmit(async (values) => {
  loading.value = true
  try {
    await api('/api/v1/auth/password-reset/request', {
      method: 'POST',
      body: { email: values.email },
    })
  }
  catch {
    // セキュリティ上、エラーでも成功と同じ表示にする
  }
  finally {
    loading.value = false
    sent.value = true
  }
})
</script>

<template>
  <div v-if="!sent">
    <p class="mb-4 text-sm text-surface-500">
      登録済みのメールアドレスを入力してください。パスワードリセット用のリンクをお送りします。
    </p>
    <form novalidate @submit.prevent="onSubmit">
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label for="email">メールアドレス</label>
          <InputText
            id="email"
            v-model="email"
            v-bind="emailProps"
            type="email"
            placeholder="example@mannschaft.app"
            :invalid="!!errors.email"
          />
          <small v-if="errors.email" class="text-red-500">{{ errors.email }}</small>
        </div>
        <Button
          type="submit"
          label="リセットメールを送信"
          icon="pi pi-envelope"
          :loading="submitting"
          :disabled="!hydrated"
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
    <p>パスワードリセットのメールを送信しました。メールをご確認ください。</p>
    <NuxtLink to="/login" class="text-sm text-primary hover:underline">
      ログインに戻る
    </NuxtLink>
  </div>
</template>
