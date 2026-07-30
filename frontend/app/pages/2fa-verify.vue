<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
// 先回りリフレッシュ武装用に runtimeConfig を setup コンテキストで capture する（armProactiveRefresh 参照）。
const runtimeConfig = useRuntimeConfig()
const notification = useNotification()
const { applyUserLocale } = useLocale()

// ハイドレーション前に入力された値（パスワードマネージャによる TOTP 自動入力を含む）を取り込む。
// InputOtp は桁ごとに id を持たない input を並べて描画するため、セレクタで連結して読む。
// ref('') のままだとハイドレーション時に空で上書きされて消える。必ずセットアップ時に読むこと。
const totpCode = ref(readPrefilledInputGroupValue('.p-inputotp-input'))
const loading = ref(false)

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()

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
    // 2FA 検証成功直後に先回りリフレッシュタイマーを武装する。
    armProactiveRefresh(runtimeConfig, authStore)
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
        :disabled="!hydrated || totpCode.length !== 6"
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
