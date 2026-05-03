<script setup lang="ts">
import type { ContactInvitePreviewResponse } from '~/types/contact'

definePageMeta({
  layout: 'default',
})

const route = useRoute()
const contactApi = useContactApi()
const authStore = useAuthStore()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const token = computed(() => route.params.token as string)
const preview = ref<ContactInvitePreviewResponse | null>(null)
const loading = ref(true)
const accepting = ref(false)
const accepted = ref(false)
const error = ref<'invalid' | 'expired' | null>(null)

async function fetchPreview() {
  loading.value = true
  try {
    const result = await contactApi.getInvitePreview(token.value)
    if (!result.data.isValid) {
      error.value = 'invalid'
    } else if (result.data.expiresAt && new Date(result.data.expiresAt) < new Date()) {
      error.value = 'expired'
    } else {
      preview.value = result.data
    }
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvite: プレビュー取得' })
    error.value = 'invalid'
  } finally {
    loading.value = false
  }
}

async function acceptInvite() {
  if (!authStore.isAuthenticated) {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  accepting.value = true
  try {
    await contactApi.acceptInvite(token.value)
    accepted.value = true
    notification.success('連絡先に追加しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactInvite: 招待承認' })
    notification.error('追加に失敗しました')
  } finally {
    accepting.value = false
  }
}

function formatExpiry(iso: string | null) {
  if (!iso) return '無期限'
  return new Date(iso).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

onMounted(fetchPreview)
</script>

<template>
  <div class="mx-auto max-w-sm px-4 py-16">
    <PageLoading v-if="loading" />

    <div v-else-if="error" class="rounded-xl border border-dashed border-gray-300 p-8 text-center">
      <i class="pi pi-exclamation-circle mb-3 text-5xl text-gray-300" />
      <h2 class="mb-1 text-lg font-semibold text-gray-700">この招待URLは無効です</h2>
      <p class="text-sm text-gray-500">
        {{
          error === 'expired'
            ? '有効期限が切れています。'
            : '利用回数を超えているか、取り消された可能性があります。'
        }}
      </p>
    </div>

    <template v-else-if="accepted">
      <div class="rounded-xl border border-green-200 bg-green-50 p-8 text-center">
        <i class="pi pi-check-circle mb-3 text-5xl text-green-500" />
        <h2 class="mb-1 text-lg font-semibold">連絡先に追加しました</h2>
        <p class="mb-4 text-sm text-gray-500">
          {{ preview?.issuer.displayName }} さんとの連絡先が追加されました
        </p>
        <Button label="チャットを開く" icon="pi pi-comments" @click="navigateTo('/chat')" />
      </div>
    </template>

    <template v-else-if="preview">
      <div
        class="flex flex-col items-center gap-6 rounded-xl border border-surface-300 p-8 text-center"
      >
        <div>
          <div class="mb-1 text-sm text-gray-400">連絡先追加の招待</div>
          <h2 class="text-xl font-bold">{{ preview.issuer.displayName }}</h2>
          <div v-if="preview.issuer.contactHandle" class="mt-0.5 text-sm text-gray-400">
            @{{ preview.issuer.contactHandle }}
          </div>
        </div>

        <div class="text-xs text-gray-400">
          <i class="pi pi-calendar mr-1" />
          有効期限: {{ formatExpiry(preview.expiresAt) }}
        </div>

        <div class="w-full flex flex-col gap-2">
          <Button
            :label="authStore.isAuthenticated ? '連絡先に追加する' : 'ログインして追加する'"
            icon="pi pi-user-plus"
            class="w-full"
            :loading="accepting"
            @click="acceptInvite"
          />
          <p class="text-xs text-gray-400">
            追加すると{{ preview.issuer.displayName }}さんとDMができるようになります
          </p>
        </div>
      </div>
    </template>
  </div>
</template>
