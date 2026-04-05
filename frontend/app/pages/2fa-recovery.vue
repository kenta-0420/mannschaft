<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const authStore = useAuthStore()
const notification = useNotification()
const { requestMfaRecovery, confirmMfaRecovery } = useAuthApi()

const step = ref<'request' | 'done'>('request')
const loading = ref(false)
const recoveryToken = ref('')

const mfaSessionToken = computed(() => route.query.session as string | undefined)

onMounted(() => {
  if (!mfaSessionToken.value) {
    navigateTo('/login')
  }
})

async function handleRequestRecovery() {
  if (!mfaSessionToken.value) return

  loading.value = true
  try {
    await requestMfaRecovery(mfaSessionToken.value)
    step.value = 'done'
    notification.success('リカバリーメールを送信しました')
  } catch {
    notification.error('リカバリーリクエストに失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleConfirmRecovery() {
  if (!recoveryToken.value) return

  loading.value = true
  try {
    const data = await confirmMfaRecovery(recoveryToken.value)
    authStore.setTokens(data.data.accessToken, data.data.refreshToken)
    navigateTo('/')
  } catch {
    notification.error('リカバリーコードが正しくありません')
    recoveryToken.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex flex-col items-center gap-6">
    <div class="text-center">
      <h2 class="mb-2 text-xl font-semibold">アカウントリカバリー</h2>
      <p class="text-sm text-surface-500">二要素認証にアクセスできない場合、リカバリーを行います</p>
    </div>

    <!-- Step 1: リカバリーリクエスト -->
    <template v-if="step === 'request'">
      <p class="text-center text-sm text-surface-500">
        登録されたメールアドレスにリカバリーリンクを送信します。
      </p>
      <Button
        label="リカバリーメールを送信"
        icon="pi pi-envelope"
        :loading="loading"
        class="w-full"
        @click="handleRequestRecovery"
      />
    </template>

    <!-- Step 2: 送信完了・トークン入力 -->
    <template v-if="step === 'done'">
      <p class="text-center text-sm text-surface-500">
        メールに記載されたリカバリートークンを入力してください。
      </p>
      <form class="flex w-full flex-col gap-4" @submit.prevent="handleConfirmRecovery">
        <InputText v-model="recoveryToken" placeholder="リカバリートークン" class="w-full" />
        <Button
          type="submit"
          label="リカバリーを確認"
          icon="pi pi-shield"
          :loading="loading"
          :disabled="!recoveryToken"
          class="w-full"
        />
      </form>
    </template>

    <div class="flex flex-col items-center gap-2 text-sm">
      <NuxtLink
        :to="mfaSessionToken ? `/2fa-verify?session=${mfaSessionToken}` : '/2fa-verify'"
        class="text-primary hover:underline"
      >
        認証コードを使用
      </NuxtLink>
      <NuxtLink to="/login" class="text-primary hover:underline"> ログインに戻る </NuxtLink>
    </div>
  </div>
</template>
