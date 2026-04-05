<script setup lang="ts">
import type { OAuthProviderResponse, UserLineStatusResponse } from '~/types/user-settings'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { getOAuthProviders, unlinkOAuthProvider, getLineStatus, unlinkLine } = useUserSettingsApi()

const loading = ref(true)
const oauthProviders = ref<OAuthProviderResponse[]>([])
const lineStatus = ref<UserLineStatusResponse | null>(null)

onMounted(async () => {
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [oauthRes, lineRes] = await Promise.all([getOAuthProviders(), getLineStatus()])
    oauthProviders.value = oauthRes.data
    lineStatus.value = lineRes.data
  } catch {
    notification.error('連携情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleUnlinkOAuth(provider: string) {
  try {
    await unlinkOAuthProvider(provider)
    oauthProviders.value = oauthProviders.value.filter((p) => p.provider !== provider)
    notification.success(`${providerLabel(provider)}の連携を解除しました`)
  } catch {
    notification.error('連携解除に失敗しました')
  }
}

async function handleUnlinkLine() {
  try {
    await unlinkLine()
    lineStatus.value = {
      ...lineStatus.value!,
      isLinked: false,
      lineUserId: null,
      displayName: null,
      pictureUrl: null,
    }
    notification.success('LINE連携を解除しました')
  } catch {
    notification.error('LINE連携の解除に失敗しました')
  }
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    google: 'Google',
    apple: 'Apple',
    github: 'GitHub',
    microsoft: 'Microsoft',
    line: 'LINE',
  }
  return labels[provider.toLowerCase()] || provider
}

function providerIcon(provider: string) {
  const icons: Record<string, string> = {
    google: 'pi pi-google',
    apple: 'pi pi-apple',
    github: 'pi pi-github',
    microsoft: 'pi pi-microsoft',
  }
  return icons[provider.toLowerCase()] || 'pi pi-link'
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">アカウント連携</h1>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <!-- OAuth連携 -->
      <SectionCard title="OAuth連携">
        <div v-if="oauthProviders.length === 0" class="py-4 text-center text-surface-400">
          連携されたアカウントはありません
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="provider in oauthProviders"
            :key="provider.provider"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-4 dark:border-surface-600"
          >
            <div class="flex items-center gap-3">
              <i :class="providerIcon(provider.provider)" class="text-xl" />
              <div>
                <p class="font-medium">{{ providerLabel(provider.provider) }}</p>
                <p class="text-sm text-surface-500">{{ provider.providerEmail }}</p>
                <p class="text-xs text-surface-400">
                  連携日: {{ formatDate(provider.connectedAt) }}
                </p>
              </div>
            </div>
            <Button
              label="解除"
              severity="danger"
              text
              size="small"
              @click="handleUnlinkOAuth(provider.provider)"
            />
          </div>
        </div>
      </SectionCard>

      <!-- LINE連携 -->
      <SectionCard title="LINE連携">
        <div v-if="lineStatus?.isLinked" class="space-y-4">
          <div class="flex items-center gap-4">
            <img
              v-if="lineStatus.pictureUrl"
              :src="lineStatus.pictureUrl"
              alt="LINEアイコン"
              class="h-12 w-12 rounded-full"
            />
            <div
              v-else
              class="flex h-12 w-12 items-center justify-center rounded-full bg-green-100 text-green-600"
            >
              <i class="pi pi-comment text-xl" />
            </div>
            <div>
              <p class="font-medium">{{ lineStatus.displayName || 'LINE ユーザー' }}</p>
              <p class="text-xs text-surface-400">連携日: {{ formatDate(lineStatus.linkedAt) }}</p>
            </div>
          </div>
          <div class="flex justify-end">
            <Button
              label="LINE連携を解除"
              severity="danger"
              outlined
              size="small"
              @click="handleUnlinkLine"
            />
          </div>
        </div>

        <div v-else class="py-4 text-center">
          <p class="mb-4 text-surface-400">LINEアカウントは連携されていません</p>
          <p class="text-sm text-surface-500">LINE連携はLINEアプリから行ってください</p>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
