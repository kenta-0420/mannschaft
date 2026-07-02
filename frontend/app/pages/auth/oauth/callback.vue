<script setup lang="ts">
definePageMeta({ layout: 'auth' })

const route = useRoute()
const router = useRouter()
const { loginWithOAuth } = useAuthApi()
const authStore = useAuthStore()
const api = useApi()
const { t } = useI18n()
const notification = useNotification()

const error = ref<string | null>(null)
const loading = ref(true)

onMounted(async () => {
  const code = route.query.code as string | undefined
  const errorParam = route.query.error as string | undefined

  if (errorParam || !code) {
    error.value = t('auth.oauth.callback_error')
    loading.value = false
    return
  }

  try {
    const res = await loginWithOAuth('google', { code })
    const tokenData = res.data
    authStore.setTokens(tokenData.accessToken, tokenData.refreshToken)

    // プロフィールを取得してユーザー情報を更新
    try {
      const profile = await api<{
        data: {
          id: number
          email: string
          lastName: string
          firstName: string
          avatarUrl: string | null
          systemRole: string | null
          locale: string
          timezone: string | null
        }
      }>('/api/v1/users/me')
      await authStore.setUser({
        id: profile.data.id,
        email: profile.data.email,
        fullName: profile.data.lastName + ' ' + profile.data.firstName,
        profileImageUrl: profile.data.avatarUrl,
        systemRole: profile.data.systemRole ?? undefined,
        timezone: profile.data.timezone ?? undefined,
        locale: profile.data.locale || undefined,
      })
    } catch {
      // プロフィール取得失敗はサイレント（トークンは取得済み）
    }

    // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
    // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
    authStore.syncAccountSettings()

    notification.success(t('auth.login.success'))
    if (authStore.isSystemAdmin) {
      await router.push('/system-admin')
    } else {
      await router.push('/dashboard')
    }
  } catch (e: unknown) {
    // 202: メール競合 → 連携確認メール送信済み
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 202) {
      await router.push('/login?oauthConflict=true')
      return
    }
    error.value = t('auth.oauth.callback_error')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="flex min-h-screen items-center justify-center">
    <div class="text-center">
      <div v-if="loading" class="space-y-4">
        <ProgressSpinner />
        <p class="text-surface-500">{{ $t('auth.oauth.processing') }}</p>
      </div>
      <div v-else-if="error" class="space-y-4">
        <i class="pi pi-exclamation-triangle text-4xl text-red-500" />
        <p class="text-lg font-medium">{{ error }}</p>
        <Button :label="$t('auth.oauth.back_to_login')" @click="$router.push('/login')" />
      </div>
    </div>
  </div>
</template>
