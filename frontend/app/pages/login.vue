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
const { t } = useI18n()

async function handleLogin() {
  loading.value = true
  try {
    const data = await api<{
      data: {
        accessToken: string
        refreshToken: string
        userId: number
        fullName: string
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
            lastName: string
            firstName: string
            avatarUrl: string | null
            systemRole: string | null
            locale: string
            timezone: string | null
            status: string | null
          }
        }>('/api/v1/users/me')
        // PENDING_PARENTAL_CONSENT: 保護者同意待ち → 専用ページへリダイレクト
        if (profile.data.status === 'PENDING_PARENTAL_CONSENT') {
          navigateTo('/parental-consent/pending')
          return
        }
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
      } catch {
        // プロフィール取得失敗時はログインレスポンスの基本情報で続行
        await authStore.setUser({
          id: data.data.userId,
          email: data.data.email,
          fullName: data.data.fullName,
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
  } catch (e: unknown) {
    // backend は AuthErrorCode を { error: { code, message } } 形式で返す。
    // code を判定して文言を出し分ける（AUTH_003 ロック等を握りつぶさない）。
    const err = e as { data?: { error?: { code?: string; message?: string } } }
    const code = err?.data?.error?.code
    if (code === 'AUTH_003') {
      // アカウントロック（5回失敗で30分ロック）
      notification.error(t('auth.login.account_locked'), t('auth.login.account_locked_detail'))
    } else if (code === 'AUTH_002') {
      // メール未確認
      notification.error(
        t('auth.login.email_not_verified'),
        t('auth.login.email_not_verified_detail'),
      )
    } else {
      // AUTH_001 を含む既定（メールアドレスまたはパスワード不一致）
      notification.error(t('auth.login.failed'), t('auth.login.invalid_credentials'))
    }
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
          v-model="password"
          input-id="password"
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
