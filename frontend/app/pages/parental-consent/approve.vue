<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const { approve, reject } = useParentalConsentApi()
const notification = useNotification()
const authStore = useAuthStore()

const token = computed(() => route.query.token as string | undefined)
const approved = ref(false)
const rejected = ref(false)
const error = ref(false)
const loading = ref(false)
const showRejectConfirm = ref(false)

async function handleApprove() {
  if (!token.value) {
    error.value = true
    return
  }
  loading.value = true
  try {
    await approve(token.value)
    approved.value = true
    notification.success(t('parental_consent.approved_message'))
  } catch (err: unknown) {
    const code = (err as { data?: { code?: string } })?.data?.code ?? ''
    if (code === 'AUTH_060') {
      error.value = true
    } else if (code === 'AUTH_062') {
      notification.error(t('parental_consent.error_auth_062'))
    } else if (code === 'AUTH_063') {
      notification.error(t('parental_consent.error_auth_063'))
    } else {
      error.value = true
    }
  } finally {
    loading.value = false
  }
}

async function handleReject() {
  if (!token.value) return
  loading.value = true
  try {
    await reject(token.value)
    rejected.value = true
    notification.success(t('parental_consent.rejected_message'))
  } catch {
    error.value = true
  } finally {
    loading.value = false
    showRejectConfirm.value = false
  }
}

// ログイン済みなら自動承認を試みる
onMounted(async () => {
  if (authStore.isAuthenticated && token.value) {
    await handleApprove()
  }
})

// U-01対応: 未ログイン時にトークンを sessionStorage に保存し、
// ログイン/登録後に戻ってきた際に自動承認する
onMounted(() => {
  if (!authStore.isAuthenticated && token.value) {
    sessionStorage.setItem('parentalConsentToken', token.value)
  }
})

function navigateToLogin() {
  navigateTo(`/login?redirect=/parental-consent/approve${token.value ? '?token=' + token.value : ''}`)
}

function navigateToRegister() {
  navigateTo(`/register?redirect=/parental-consent/approve${token.value ? '?token=' + token.value : ''}`)
}
</script>

<template>
  <div class="max-w-md mx-auto py-12 px-4 text-center">
    <!-- 承認済み -->
    <div v-if="approved" class="text-green-600">
      <p class="text-xl font-semibold">{{ $t('parental_consent.approved_message') }}</p>
    </div>

    <!-- 否認済み -->
    <div v-else-if="rejected" class="text-gray-600">
      <p class="text-xl font-semibold">{{ $t('parental_consent.rejected_message') }}</p>
    </div>

    <!-- エラー -->
    <div v-else-if="error" class="text-red-600">
      <p class="text-xl font-semibold">{{ $t('parental_consent.invalid_token') }}</p>
    </div>

    <!-- トークンなし -->
    <div v-else-if="!token" class="text-gray-600">
      <p>{{ $t('parental_consent.invalid_token') }}</p>
    </div>

    <!-- 未ログイン時 -->
    <div v-else-if="!authStore.isAuthenticated" class="space-y-4">
      <h1 class="text-2xl font-bold">{{ $t('parental_consent.approve_title') }}</h1>
      <p class="text-gray-600">{{ $t('parental_consent.login_required') }}</p>
      <div class="flex flex-col gap-3">
        <button
          class="w-full px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700"
          @click="navigateToLogin"
        >
          {{ $t('parental_consent.login_button') }}
        </button>
        <button
          class="w-full px-4 py-2 border border-gray-300 rounded-md hover:bg-gray-50"
          @click="navigateToRegister"
        >
          {{ $t('parental_consent.register_button') }}
        </button>
      </div>
    </div>

    <!-- ログイン済み・処理中 -->
    <div v-else>
      <div v-if="loading" class="text-gray-400">...</div>
      <div v-else>
        <h1 class="text-2xl font-bold mb-4">{{ $t('parental_consent.approve_title') }}</h1>
        <p class="text-gray-600 mb-6">{{ $t('parental_consent.approve_description') }}</p>
        <div class="flex gap-3 justify-center">
          <button
            class="px-6 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700"
            @click="handleApprove"
          >
            {{ $t('parental_consent.approve_button') }}
          </button>
          <button
            class="px-6 py-2 border border-red-300 text-red-600 rounded-md hover:bg-red-50"
            @click="showRejectConfirm = true"
          >
            {{ $t('parental_consent.reject_button') }}
          </button>
        </div>
        <!-- 否認確認ダイアログ -->
        <div v-if="showRejectConfirm" class="mt-6 p-4 bg-red-50 border border-red-200 rounded-lg">
          <p class="text-sm text-red-700 mb-3">{{ $t('parental_consent.reject_confirm') }}</p>
          <div class="flex gap-2 justify-center">
            <button
              class="px-4 py-1.5 bg-red-600 text-white rounded text-sm"
              @click="handleReject"
            >
              {{ $t('parental_consent.reject_button') }}
            </button>
            <button
              class="px-4 py-1.5 border border-gray-300 rounded text-sm"
              @click="showRejectConfirm = false"
            >
              {{ $t('button.cancel') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
