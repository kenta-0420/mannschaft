<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const authStore = useAuthStore()
// 先回りリフレッシュ武装用に runtimeConfig を setup コンテキストで capture する（armProactiveRefresh 参照）。
const runtimeConfig = useRuntimeConfig()
const notification = useNotification()
const { t } = useI18n()
const { requestMfaRecovery, confirmMfaRecovery } = useAuthApi()

const mfaSessionToken = computed(() => route.query.session as string | undefined)
// メールのリンク（/2fa-recovery?token=...）で届くリカバリートークン。セッション無しでも開ける（AC-52）。
const linkToken = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))

// request: メール送信前 / done: 送信済み・トークン手入力 / confirm: リンクのトークンで確認待ち
const step = ref<'request' | 'done' | 'confirm'>(linkToken.value ? 'confirm' : 'request')
const loading = ref(false)
const recoveryToken = ref(linkToken.value)
const confirmFailed = ref(false)
// MFA セッションが不在・期限切れ（AUTH_073）。このセッションでは回復を要求できないので、ログインからやり直させる。
const sessionExpired = ref(false)

interface ApiErrorShape {
  data?: { error?: { code?: string } }
}

function errorCodeOf(e: unknown): string | undefined {
  return (e as ApiErrorShape)?.data?.error?.code
}

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()
// ハイドレーション待ちの間もボタンをローディング表示にする（無反応に見える問題の解消）。
// :disabled="!hydrated" は Enter キーによる implicit submission 抑止のため別途維持する
// （PrimeVue の loading は内部的に disabled 相当になるが、明示指定で確実に塞ぐ）。
// Step 1 は <form> の外にあり hydrated ガード対象外のため loading をそのまま使う。
const submitting = computed(() => loading.value || !hydrated.value)

onMounted(() => {
  // セッションもトークンも無ければ、このページでできることが無いのでログインへ戻す。
  if (!mfaSessionToken.value && !linkToken.value) {
    navigateTo('/login')
  }
})

async function handleRequestRecovery() {
  if (!mfaSessionToken.value || loading.value) return

  loading.value = true
  try {
    await requestMfaRecovery(mfaSessionToken.value)
    step.value = 'done'
    notification.success(t('auth.mfa.recovery.sent'))
  } catch (e: unknown) {
    // AUTH_073: MFA セッションの不在・期限切れ（/2fa/validate と同じ。ALIC-4）。再試行しても通らないので再ログインへ誘導する。
    if (errorCodeOf(e) === 'AUTH_073') {
      sessionExpired.value = true
      notification.error(t('auth.mfa.error_session_expired'))
      return
    }
    notification.error(t('auth.mfa.recovery.request_failed'))
  } finally {
    loading.value = false
  }
}

async function handleConfirmRecovery() {
  if (!recoveryToken.value || loading.value) return

  loading.value = true
  confirmFailed.value = false
  try {
    const data = await confirmMfaRecovery(recoveryToken.value)
    authStore.setTokens(data.data.accessToken, data.data.refreshToken)
    // リカバリーログイン成功直後に先回りリフレッシュタイマーを武装する。
    armProactiveRefresh(runtimeConfig, authStore)
    navigateTo('/')
  } catch {
    // 期限切れ・使用済み・不正トークン。同じトークンでの再試行は無意味なのでログインからやり直させる。
    confirmFailed.value = true
    notification.error(t('auth.mfa.recovery.confirm_failed'))
    recoveryToken.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex flex-col items-center gap-6">
    <div class="text-center">
      <h2 class="mb-2 text-xl font-semibold">{{ t('auth.mfa.recovery.title') }}</h2>
      <p class="text-sm text-surface-500">{{ t('auth.mfa.recovery.subtitle') }}</p>
    </div>

    <!-- Step 1: リカバリーリクエスト -->
    <template v-if="step === 'request'">
      <p class="text-center text-sm text-surface-500">
        {{ t('auth.mfa.recovery.request_description') }}
      </p>
      <Message
        v-if="sessionExpired"
        severity="error"
        :closable="false"
        class="w-full"
        data-testid="mfa-recovery-session-expired"
      >
        {{ t('auth.mfa.error_session_expired') }}
      </Message>
      <Button
        v-else
        :label="t('auth.mfa.recovery.request_submit')"
        icon="pi pi-envelope"
        :loading="loading"
        class="w-full"
        @click="handleRequestRecovery"
      />
    </template>

    <!-- Step 2a: メールのリンクから開いた。トークンは URL から受けているので押すだけで確認できる -->
    <template v-if="step === 'confirm'">
      <p class="text-center text-sm text-surface-500">
        {{ t('auth.mfa.recovery.confirm_description') }}
      </p>
      <Message v-if="confirmFailed" severity="error" :closable="false" class="w-full">
        {{ t('auth.mfa.recovery.confirm_failed_detail') }}
      </Message>
      <Button
        v-else
        :label="t('auth.mfa.recovery.confirm_submit')"
        icon="pi pi-shield"
        :loading="submitting"
        :disabled="!hydrated || !recoveryToken"
        class="w-full"
        data-testid="mfa-recovery-confirm"
        @click="handleConfirmRecovery"
      />
    </template>

    <!-- Step 2b: 送信完了・トークン手入力 -->
    <template v-if="step === 'done'">
      <p class="text-center text-sm text-surface-500">
        {{ t('auth.mfa.recovery.token_description') }}
      </p>
      <form class="flex w-full flex-col gap-4" @submit.prevent="handleConfirmRecovery">
        <InputText
          v-model="recoveryToken"
          :placeholder="t('auth.mfa.recovery.token_placeholder')"
          class="w-full"
        />
        <Button
          type="submit"
          :label="t('auth.mfa.recovery.confirm_submit')"
          icon="pi pi-shield"
          :loading="submitting"
          :disabled="!hydrated || !recoveryToken"
          class="w-full"
        />
      </form>
    </template>

    <div class="flex flex-col items-center gap-2 text-sm">
      <NuxtLink
        v-if="mfaSessionToken && !sessionExpired"
        :to="{
          path: '/2fa-verify',
          query: {
            session: mfaSessionToken,
            primary: typeof route.query.primary === 'string' ? route.query.primary : undefined,
            methods: typeof route.query.methods === 'string' ? route.query.methods : undefined,
            redirect: typeof route.query.redirect === 'string' ? route.query.redirect : undefined,
          },
        }"
        class="text-primary hover:underline"
      >
        {{ t('auth.mfa.recovery.use_code') }}
      </NuxtLink>
      <NuxtLink to="/login" class="text-primary hover:underline">
        {{ t('auth.mfa.back_to_login') }}
      </NuxtLink>
    </div>
  </div>
</template>
