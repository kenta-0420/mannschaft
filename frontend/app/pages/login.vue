<script setup lang="ts">
definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const email = ref('')
const password = ref('')
const loading = ref(false)

const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const route = useRoute()
const { applyUserLocale } = useLocale()

async function handleLogin() {
  loading.value = true
  try {
    const data = await api<{
      data: {
        accessToken: string
        refreshToken: string
        userId: number
        displayName: string
        email: string
        mfaRequired?: boolean
        mfaSessionToken?: string
        reactivated?: boolean
      }
    }>('/api/v1/auth/login', {
      method: 'POST',
      body: { email: email.value, password: password.value },
    })
    if (data.data.mfaRequired) {
      navigateTo(`/2fa-verify?session=${data.data.mfaSessionToken}`)
    } else {
      authStore.setTokens(data.data.accessToken, data.data.refreshToken)

      // /api/v1/users/me でフルプロフィール（systemRole・locale・avatarUrl 等）を一括取得
      try {
        const profile = await api<{
          data: {
            id: number
            email: string
            displayName: string
            avatarUrl: string | null
            systemRole: string | null
            locale: string
          }
        }>('/api/v1/users/me')
        authStore.setUser({
          id: profile.data.id,
          email: profile.data.email,
          displayName: profile.data.displayName,
          profileImageUrl: profile.data.avatarUrl,
          systemRole: profile.data.systemRole ?? undefined,
        })
        if (profile.data.locale) {
          await applyUserLocale(profile.data.locale)
        }
      } catch {
        // プロフィール取得失敗時はログインレスポンスの基本情報で続行
        authStore.setUser({
          id: data.data.userId,
          email: data.data.email,
          displayName: data.data.displayName,
          profileImageUrl: null,
        })
      }

      if (data.data.reactivated) {
        notification.success('ログインしました')
        navigateTo('/account-restore')
      } else {
        notification.success('ログイン成功')
        const redirect = route.query.redirect as string
        if (redirect) {
          navigateTo(redirect)
        } else if (authStore.isSystemAdmin) {
          navigateTo('/system-admin')
        } else {
          navigateTo('/')
        }
      }
    }
  } catch {
    notification.error(
      'ログインに失敗しました',
      'メールアドレスまたはパスワードが正しくありません。',
    )
  } finally {
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
          input-id="password"
          v-model="password"
          :feedback="false"
          toggle-mask
          fluid
          required
        />
      </div>
      <Button type="submit" label="ログイン" icon="pi pi-sign-in" :loading="loading" class="mt-2" />
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
