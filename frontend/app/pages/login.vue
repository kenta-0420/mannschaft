<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const email = ref('')
const password = ref('')
const loading = ref(false)

const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const route = useRoute()

async function handleLogin() {
  loading.value = true
  try {
    const data = await api<{
      data: {
        accessToken: string
        refreshToken: string
        user: { id: number; email: string; displayName: string; profileImageUrl: string | null }
        mfaRequired?: boolean
        mfaSessionToken?: string
      }
    }>('/api/v1/auth/login', {
      method: 'POST',
      body: { email: email.value, password: password.value },
    })
    if (data.data.mfaRequired) {
      navigateTo(`/2fa-verify?session=${data.data.mfaSessionToken}`)
    }
    else {
      authStore.setTokens(data.data.accessToken, data.data.refreshToken)
      authStore.setUser(data.data.user)
      notification.success('ログイン成功')
      const redirect = (route.query.redirect as string) || '/'
      navigateTo(redirect)
    }
  }
  catch {
    notification.error('ログインに失敗しました', 'メールアドレスまたはパスワードが正しくありません。')
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <form @submit.prevent="handleLogin">
    <div class="flex flex-col gap-4">
      <div class="flex flex-col gap-2">
        <label for="email">メールアドレス</label>
        <InputText
          id="email"
          v-model="email"
          type="email"
          placeholder="example@mannschaft.app"
          required
        />
      </div>
      <div class="flex flex-col gap-2">
        <label for="password">パスワード</label>
        <Password
          id="password"
          v-model="password"
          :feedback="false"
          toggle-mask
          fluid
          required
        />
      </div>
      <Button
        type="submit"
        label="ログイン"
        icon="pi pi-sign-in"
        :loading="loading"
        class="mt-2"
      />
      <div class="flex flex-col items-center gap-2">
        <NuxtLink to="/forgot-password" class="text-sm text-primary hover:underline">
          パスワードをお忘れですか？
        </NuxtLink>
        <NuxtLink to="/register" class="text-sm text-primary hover:underline">
          新規アカウント作成
        </NuxtLink>
      </div>
    </div>
  </form>
</template>
