<script setup lang="ts">
import type { CareLinkInvitationResponse } from '~/types/careLink'

// このページは認証不要（招待リンクは誰でもアクセス可能）
definePageMeta({ auth: false })

const { t } = useI18n()
const api = useCareLinkApi()
const notification = useNotification()
const route = useRoute()
const router = useRouter()

const token = computed(() => route.params.token as string)

const invitation = ref<CareLinkInvitationResponse | null>(null)
const loading = ref(true)
const loadError = ref(false)
const processing = ref(false)
const done = ref(false)
const doneAction = ref<'accept' | 'reject' | null>(null)

async function loadInvitation() {
  loading.value = true
  loadError.value = false
  try {
    const result = await api.getInvitationByToken(token.value)
    invitation.value = result.data
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function accept() {
  processing.value = true
  try {
    await api.acceptInvitation(token.value)
    notification.success(t('care.message.acceptSuccess'))
    doneAction.value = 'accept'
    done.value = true
  } catch {
    notification.error(t('care.message.acceptError'))
  } finally {
    processing.value = false
  }
}

async function reject() {
  processing.value = true
  try {
    await api.rejectInvitation(token.value)
    notification.success(t('care.message.rejectSuccess'))
    doneAction.value = 'reject'
    done.value = true
  } catch {
    notification.error(t('care.message.rejectError'))
  } finally {
    processing.value = false
  }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString()
}

onMounted(() => loadInvitation())
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-surface-50 p-4 dark:bg-surface-900">
    <div class="w-full max-w-md">
      <!-- ロード中 -->
      <div v-if="loading" class="flex flex-col items-center gap-4 p-8">
        <ProgressSpinner />
        <p class="text-surface-500">{{ $t('common.button.loading') }}</p>
      </div>

      <!-- エラー -->
      <div
        v-else-if="loadError"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-exclamation-triangle mb-4 text-4xl text-red-400" />
        <h2 class="mb-2 text-lg font-semibold">
          {{ $t('care.message.invalidToken') }}
        </h2>
        <Button
          :label="$t('common.button.back')"
          severity="secondary"
          class="mt-4"
          @click="router.push('/')"
        />
      </div>

      <!-- 完了 -->
      <div
        v-else-if="done"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i
          class="pi mb-4 text-4xl"
          :class="doneAction === 'accept' ? 'pi-check-circle text-green-500' : 'pi-times-circle text-red-400'"
        />
        <h2 class="mb-2 text-lg font-semibold">
          {{
            doneAction === 'accept'
              ? $t('care.message.acceptSuccess')
              : $t('care.message.rejectSuccess')
          }}
        </h2>
        <Button
          :label="$t('common.button.close')"
          severity="secondary"
          class="mt-4"
          @click="router.push('/')"
        />
      </div>

      <!-- 招待詳細 -->
      <div
        v-else-if="invitation"
        class="rounded-lg border border-surface-200 bg-white p-8 dark:border-surface-700 dark:bg-surface-800"
      >
        <h1 class="mb-4 text-xl font-bold">
          {{ $t('care.page.invitationAccept') }}
        </h1>

        <div class="mb-6 flex flex-col gap-3 rounded-lg bg-surface-50 p-4 dark:bg-surface-700">
          <div class="flex items-center justify-between">
            <span class="text-sm text-surface-500">{{ $t('care.label.inviterName') }}</span>
            <span class="font-medium">{{ invitation.inviterDisplayName }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-surface-500">{{ $t('care.label.careCategory') }}</span>
            <span>{{ $t(`care.category.${invitation.careCategory}`) }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-surface-500">{{ $t('care.label.relationship') }}</span>
            <span>{{ $t(`care.relationship.${invitation.relationship}`) }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-surface-500">{{ $t('care.label.expiresAt') }}</span>
            <span class="text-sm">{{ formatDate(invitation.expiresAt) }}</span>
          </div>
        </div>

        <div class="flex gap-3">
          <Button
            :label="$t('care.button.rejectInvitation')"
            severity="secondary"
            outlined
            :loading="processing"
            class="flex-1"
            @click="reject"
          />
          <Button
            :label="$t('care.button.acceptInvitation')"
            icon="pi pi-check"
            :loading="processing"
            class="flex-1"
            @click="accept"
          />
        </div>
      </div>
    </div>
  </div>
</template>
