<script setup lang="ts">
import type { EmailOutboxDetail } from '~/composables/useEmailOutboxAdminApi'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { fetchDetail, retryDeadLetter, cancelPending } = useEmailOutboxAdminApi()
const { error: showError, success: showSuccess } = useNotification()
const { formatDateTime: formatDateTimeBase } = useDatetime()

const outboxId = computed(() => String(route.params.id))

const detail = ref<EmailOutboxDetail | null>(null)
const loading = ref(false)
const actionLoading = ref(false)
const notFound = ref(false)

async function load() {
  loading.value = true
  notFound.value = false
  try {
    const res = await fetchDetail(outboxId.value)
    detail.value = res.data
  } catch (err: unknown) {
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 404) {
      notFound.value = true
    } else {
      showError(t('email_outbox.load_failed'))
    }
    console.error(err)
  } finally {
    loading.value = false
  }
}

async function onRetry() {
  if (!detail.value) return
  actionLoading.value = true
  try {
    await retryDeadLetter(detail.value.id)
    showSuccess(t('email_outbox.retry_success'))
    await load()
  } catch (err: unknown) {
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 409) {
      showError(t('email_outbox.retry_conflict'))
    } else {
      showError(t('email_outbox.load_failed'))
    }
  } finally {
    actionLoading.value = false
  }
}

async function onCancel() {
  if (!detail.value) return
  actionLoading.value = true
  try {
    await cancelPending(detail.value.id)
    showSuccess(t('email_outbox.cancel_success'))
    await load()
  } catch (err: unknown) {
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 409) {
      showError(t('email_outbox.cancel_conflict'))
    } else {
      showError(t('email_outbox.load_failed'))
    }
  } finally {
    actionLoading.value = false
  }
}

function formatDateTime(dt: string | null): string {
  if (!dt) return t('email_outbox.na')
  return formatDateTimeBase(dt)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="container mx-auto max-w-4xl space-y-4 p-4">
    <!-- ヘッダー -->
    <header class="flex items-center justify-between">
      <Button
        :label="t('email_outbox.action.back')"
        icon="pi pi-arrow-left"
        text
        size="small"
        @click="router.push('/system-admin/email-outbox')"
      />
    </header>

    <!-- ローディング -->
    <div v-if="loading" class="py-12 text-center text-sm text-surface-500">
      <i class="pi pi-spin pi-spinner mr-2" aria-hidden="true" />
    </div>

    <!-- 404 -->
    <div
      v-else-if="notFound"
      class="rounded-xl border border-surface-300 bg-surface-0 p-8 text-center text-sm text-surface-500 dark:border-surface-600 dark:bg-surface-800"
    >
      <i class="pi pi-exclamation-triangle mb-2 text-4xl text-orange-500" aria-hidden="true" />
      <div>{{ t('email_outbox.no_data') }}</div>
    </div>

    <!-- 詳細 -->
    <template v-else-if="detail">
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="mb-4 flex items-center justify-between">
          <h1 class="text-lg font-bold">{{ t('email_outbox.detail.title') }}</h1>
          <!-- アクションボタン -->
          <div class="flex gap-2">
            <Button
              v-if="detail.status === 'DEAD_LETTER'"
              :label="t('email_outbox.action.retry')"
              icon="pi pi-refresh"
              severity="warning"
              :loading="actionLoading"
              @click="onRetry"
            />
            <Button
              v-if="detail.status === 'PENDING'"
              :label="t('email_outbox.action.cancel')"
              icon="pi pi-times"
              severity="danger"
              :loading="actionLoading"
              @click="onCancel"
            />
          </div>
        </div>

        <!-- GDPR 消去通知 -->
        <div
          v-if="detail.bodyPurgedAt"
          class="mb-4 flex items-center gap-2 rounded-lg bg-orange-50 px-4 py-3 text-sm text-orange-700 dark:bg-orange-900/30 dark:text-orange-400"
        >
          <i class="pi pi-info-circle" aria-hidden="true" />
          {{ t('email_outbox.detail.body_purged') }}
        </div>

        <!-- ステータスバッジ -->
        <div class="mb-6">
          <span
            :class="{
              'rounded-full px-3 py-1 text-sm font-semibold': true,
              'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300':
                detail.status === 'PENDING',
              'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300':
                detail.status === 'SENDING',
              'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300':
                detail.status === 'SENT',
              'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300':
                detail.status === 'FAILED',
              'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300':
                detail.status === 'DEAD_LETTER',
              'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-400':
                detail.status === 'CANCELLED',
            }"
          >
            {{ t(`email_outbox.status.${detail.status}`) }}
          </span>
        </div>

        <!-- 基本情報グリッド -->
        <dl class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.table.id') }}</dt>
            <dd class="mt-1 font-mono text-sm">{{ detail.id }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.to_address') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.toAddress }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.table.template') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.templateKind }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.locale') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.locale }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.source_domain') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.sourceDomain }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.source_event_id') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.sourceEventId ?? t('email_outbox.na') }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.table.retry_count') }}</dt>
            <dd class="mt-1 text-sm">{{ detail.retryCount }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.ses_message_id') }}</dt>
            <dd class="mt-1 font-mono text-sm">{{ detail.sesMessageId ?? t('email_outbox.na') }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.table.created_at') }}</dt>
            <dd class="mt-1 text-sm">{{ formatDateTime(detail.createdAt) }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.next_attempt_at') }}</dt>
            <dd class="mt-1 text-sm">{{ formatDateTime(detail.nextAttemptAt) }}</dd>
          </div>
          <div>
            <dt class="text-sm font-medium text-surface-500">{{ t('email_outbox.detail.sent_at') }}</dt>
            <dd class="mt-1 text-sm">{{ formatDateTime(detail.sentAt) }}</dd>
          </div>
          <div v-if="detail.lastError">
            <dt class="text-sm font-medium text-red-500">{{ t('email_outbox.detail.last_error') }}</dt>
            <dd class="mt-1 text-sm text-red-600 dark:text-red-400">{{ detail.lastError }}</dd>
          </div>
        </dl>

        <!-- テンプレート変数 -->
        <div v-if="!detail.bodyPurgedAt && Object.keys(detail.payloadVars).length > 0" class="mt-6">
          <h2 class="mb-2 text-sm font-semibold">{{ t('email_outbox.detail.payload_vars') }}</h2>
          <div class="overflow-hidden rounded-lg border border-surface-300 dark:border-surface-600">
            <table class="w-full text-sm">
              <thead class="border-b border-surface-300 bg-surface-50 dark:border-surface-600 dark:bg-surface-700">
                <tr>
                  <th class="px-4 py-2 text-left font-medium text-surface-500">キー</th>
                  <th class="px-4 py-2 text-left font-medium text-surface-500">値</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(value, key) in detail.payloadVars"
                  :key="key"
                  class="border-b border-surface-200 dark:border-surface-700"
                >
                  <td class="px-4 py-2 font-mono text-xs">{{ key }}</td>
                  <td class="px-4 py-2 text-xs">{{ value }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
