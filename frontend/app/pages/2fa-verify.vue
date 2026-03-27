<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()

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
        user: { id: number; email: string; displayName: string; profileImageUrl: string | null }
      }
    }>('/api/v1/auth/2fa/validate', {
      method: 'POST',
      body: {
        mfaSessionToken: mfaSessionToken.value,
        totpCode: totpCode.value,
      },
    })
    authStore.setTokens(data.data.accessToken, data.data.refreshToken)
    authStore.setUser(data.data.user)
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
