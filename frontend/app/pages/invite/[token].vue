<script setup lang="ts">
definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const token = computed(() => String(route.params.token))

interface InvitePreview {
  id: number
  name: string
  type: 'ORGANIZATION' | 'TEAM'
  description: string | null
  iconUrl: string | null
  roleName: string
  expiresAt: string | null
  isValid: boolean
}

const preview = ref<InvitePreview | null>(null)
const loading = ref(false)
const joining = ref(false)
const error = ref(false)

const roleLabel: Record<string, string> = {
  ADMIN: '管理者',
  DEPUTY_ADMIN: '副管理者',
  MEMBER: 'メンバー',
  SUPPORTER: 'サポーター',
  GUEST: 'ゲスト',
}

const typeLabel: Record<string, string> = {
  ORGANIZATION: '組織',
  TEAM: 'チーム',
}

async function fetchPreview() {
  loading.value = true
  error.value = false
  try {
    const result = await api<{ data: InvitePreview }>(`/api/v1/invite/${token.value}`)
    preview.value = result.data
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

async function joinWithToken() {
  joining.value = true
  try {
    await api(`/api/v1/invite/${token.value}/join`, { method: 'POST' })
    notification.success(`${typeLabel[preview.value!.type]}に参加しました`)
    navigateTo('/dashboard')
  } catch (err) {
    handleApiError(err, '招待参加')
  } finally {
    joining.value = false
  }
}

function goToLogin() {
  navigateTo(`/login?redirect=/invite/${token.value}`)
}

function goToRegister() {
  navigateTo(`/register?invite=${token.value}`)
}

function formatExpiry(expiresAt: string | null): string {
  if (!expiresAt) return '無期限'
  const date = new Date(expiresAt)
  return `${date.getFullYear()}/${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')} まで有効`
}

onMounted(() => {
  fetchPreview()
})
</script>

<template>
  <div class="flex min-h-screen items-center justify-center p-4">
    <PageLoading v-if="loading" />

    <div v-else-if="error" class="w-full max-w-md rounded-lg border p-8 text-center">
      <i class="pi pi-exclamation-triangle mb-4 text-5xl text-yellow-500" />
      <h2 class="mb-2 text-xl font-bold">招待リンクが無効です</h2>
      <p class="mb-6 text-gray-500">このリンクは無効か、既に期限切れです。</p>
      <Button label="ホームに戻る" icon="pi pi-home" @click="navigateTo('/')" />
    </div>

    <div v-else-if="preview" class="w-full max-w-md rounded-lg border p-8">
      <!-- 無効な招待 -->
      <template v-if="!preview.isValid">
        <div class="text-center">
          <i class="pi pi-times-circle mb-4 text-5xl text-red-500" />
          <h2 class="mb-2 text-xl font-bold">この招待リンクは無効です</h2>
          <p class="mb-6 text-gray-500">招待リンクが期限切れか、既に無効化されています。</p>
          <Button label="ホームに戻る" icon="pi pi-home" @click="navigateTo('/')" />
        </div>
      </template>

      <!-- 有効な招待 -->
      <template v-else>
        <div class="text-center">
          <Avatar
            :image="preview.iconUrl ?? undefined"
            :label="preview.iconUrl ? undefined : preview.name.charAt(0)"
            shape="circle"
            size="xlarge"
            class="mb-4"
          />
          <Tag :value="typeLabel[preview.type]" severity="info" class="mb-2" />
          <h2 class="mb-1 text-xl font-bold">
            {{ preview.name }}
          </h2>
          <p v-if="preview.description" class="mb-4 text-sm text-gray-600">
            {{ preview.description }}
          </p>

          <div class="mb-6 space-y-2 rounded-lg bg-gray-50 p-4 text-sm">
            <div class="flex items-center justify-between">
              <span class="text-gray-500">参加ロール</span>
              <span class="font-medium">{{ roleLabel[preview.roleName] ?? preview.roleName }}</span>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-gray-500">有効期限</span>
              <span class="font-medium">{{ formatExpiry(preview.expiresAt) }}</span>
            </div>
          </div>

          <!-- ログイン済み -->
          <Button
            v-if="authStore.isAuthenticated"
            label="参加する"
            icon="pi pi-check"
            class="w-full"
            :loading="joining"
            @click="joinWithToken"
          />

          <!-- 未ログイン -->
          <template v-else>
            <Button
              label="ログインして参加"
              icon="pi pi-sign-in"
              class="w-full"
              @click="goToLogin"
            />
            <Button
              label="アカウントをお持ちでない方"
              icon="pi pi-user-plus"
              severity="secondary"
              class="mt-2 w-full"
              @click="goToRegister"
            />
          </template>
        </div>
      </template>
    </div>
  </div>
</template>
