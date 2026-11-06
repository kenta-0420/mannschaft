<script setup lang="ts">
import { inviteRedirect } from '~/utils/inviteRedirect'
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const returnPath = computed(() => inviteRedirect(route.query.redirect))
const loginTarget = computed(() => ({
  path: '/login',
  query: returnPath.value ? { redirect: returnPath.value } : {},
}))
const api = useApi()
const notification = useNotification()

const token = computed(() => route.query.token as string | undefined)
const email = computed(() => route.query.email as string | undefined)

const verifying = ref(false)
const verified = ref(false)
const verifyError = ref(false)
const resending = ref(false)

async function verifyToken() {
  if (!token.value) return
  verifying.value = true
  verifyError.value = false
  try {
    await api('/api/v1/auth/verify-email', {
      method: 'POST',
      body: { token: token.value },
    })
    verified.value = true
    notification.success('メール認証が完了しました')
    setTimeout(() => navigateTo(loginTarget.value), 2000)
  }
  catch {
    verifyError.value = true
    notification.error('メール認証に失敗しました', 'リンクが無効または期限切れです。')
  }
  finally {
    verifying.value = false
  }
}

async function resendVerification() {
  if (!email.value) return
  resending.value = true
  try {
    await api('/api/v1/auth/verify-email/resend', {
      method: 'POST',
      body: { email: email.value },
    })
    notification.success('確認メールを再送信しました')
  }
  catch {
    notification.error('再送信に失敗しました', 'しばらく時間をおいて再度お試しください。')
  }
  finally {
    resending.value = false
  }
}

onMounted(() => {
  if (token.value) {
    verifyToken()
  }
})
</script>

<template>
  <div class="flex flex-col items-center gap-6">
    <!-- トークン検証中 -->
    <template v-if="token">
      <template v-if="verifying">
        <LoadingBounce />
        <p class="text-center text-lg">メールアドレスを認証しています...</p>
      </template>

      <template v-else-if="verified">
        <i class="pi pi-check-circle text-5xl text-green-500" />
        <h2 class="text-xl font-bold">認証が完了しました</h2>
        <p class="text-center text-surface-500">
          メールアドレスの認証が完了しました。ログインしてください。
        </p>
        <Button
          label="ログインへ"
          icon="pi pi-sign-in"
          class="w-full"
          @click="navigateTo(loginTarget)"
        />
      </template>

      <template v-else-if="verifyError">
        <i class="pi pi-times-circle text-5xl text-red-500" />
        <h2 class="text-xl font-bold">認証に失敗しました</h2>
        <p class="text-center text-surface-500">
          リンクが無効または期限切れです。確認メールを再送信してください。
        </p>
        <Button
          v-if="email"
          label="確認メールを再送信"
          icon="pi pi-envelope"
          :loading="resending"
          class="w-full"
          @click="resendVerification"
        />
        <NuxtLink :to="loginTarget" class="text-sm text-primary hover:underline">
          ログインページへ戻る
        </NuxtLink>
      </template>
    </template>

    <!-- トークンなし（登録直後の案内画面） -->
    <template v-else>
      <i class="pi pi-envelope text-5xl text-primary" />
      <h2 class="text-xl font-bold">確認メールを送信しました</h2>
      <p class="text-center text-surface-500">
        <span v-if="email" class="font-semibold">{{ email }}</span>
        に確認メールを送信しました。メール内のリンクをクリックして認証を完了してください。
      </p>
      <Button
        v-if="email"
        label="確認メールを再送信"
        icon="pi pi-envelope"
        outlined
        :loading="resending"
        class="w-full"
        @click="resendVerification"
      />
      <NuxtLink :to="loginTarget" class="text-sm text-primary hover:underline">
        ログインページへ戻る
      </NuxtLink>
    </template>
  </div>
</template>
