<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const { applyUserLocale } = useLocale()

const totpCode = ref('')
const loading = ref(false)

const mfaSessionToken = computed(() => route.query.session as string | undefined)

onMounted(() => {
  if (!mfaSessionToken.value) {
    navigateTo('/login')
  }
})

async function handleVerify() {
  if (!mfaSessionToken.value || totpCode.value.length !== 6) return

  loading.value = true
  try {
    const data = await api<{
      data: {
        accessToken: string
        refreshToken: string
        user: { id: number; email: string; fullName: string; profileImageUrl: string | null }
      }
    }>('/api/v1/auth/2fa/validate', {
      method: 'POST',
      body: {
        mfaSessionToken: mfaSessionToken.value,
        totpCode: totpCode.value,
      },
    })
    authStore.setTokens(data.data.accessToken, data.data.refreshToken)
    await authStore.setUser(data.data.user)

    // フルプロフィール（timezone・locale 等）を取得して store を更新
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
      })
      if (profile.data.locale) {
        await applyUserLocale(profile.data.locale)
      }
    }
    catch {
      // プロフィール取得失敗時は 2FA 認証レスポンスの基本情報で続行
    }

    // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
    // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
    authStore.syncAccountSettings()

    navigateTo('/')
  }
  catch {
    notification.error('認証コードが正しくありません')
    totpCode.value = ''
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <form @submit.prevent="handleVerify">
    <div class="flex flex-col items-center gap-6">
      <div class="text-center">
        <h2 class="text-xl font-semibold mb-2">
          二要素認証
        </h2>
        <p class="text-sm text-surface-500">
          認証アプリに表示されている6桁のコードを入力してください
        </p>
      </div>

      <InputOtp
        v-model="totpCode"
        :length="6"
        integer-only
      />

      <Button
        type="submit"
        label="認証する"
        icon="pi pi-shield"
        :loading="loading"
        :disabled="totpCode.length !== 6"
        class="w-full"
      />

      <div class="flex flex-col items-center gap-2 text-sm">
        <NuxtLink to="/2fa-recovery" class="text-primary hover:underline">
          リカバリーコードを使用
        </NuxtLink>
        <NuxtLink to="/login" class="text-primary hover:underline">
          ログインに戻る
        </NuxtLink>
      </div>
    </div>
  </form>
</template>
