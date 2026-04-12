<script setup lang="ts">
import type {
  ConfirmableNotificationSummary,
  ConfirmableNotificationStatus,
} from '~/types/confirmable'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const { listNotifications, cancelNotification } = useConfirmableNotificationApi()
const { showError } = useNotification()
const { t } = useI18n()
const { relativeTime } = useRelativeTime()

const notifications = ref<ConfirmableNotificationSummary[]>([])
const loading = ref(false)
const cancelling = ref<number | null>(null)

/** 選択中の通知（受信者パネル表示用） */
const selectedNotificationId = ref<number | null>(null)

/** 発信履歴を取得する */
async function loadNotifications() {
  loading.value = true
  try {
    const res = await listNotifications(props.scopeType, props.scopeId)
    notifications.value = res.data
  } catch (err) {
    console.error('確認通知一覧の取得に失敗しました', err)
    showError(t('confirmable.load_error'))
  } finally {
    loading.value = false
  }
}

/** 確認通知をキャンセルする */
async function onCancel(notificationId: number) {
  cancelling.value = notificationId
  try {
    await cancelNotification(props.scopeType, props.scopeId, notificationId)
    // キャンセル後に一覧を再取得
    await loadNotifications()
    const toast = useToast()
    toast.add({ severity: 'success', summary: t('dialog.success'), life: 3000 })
  } catch (err) {
    console.error('確認通知のキャンセルに失敗しました', err)
    showError(t('confirmable.cancel_error'))
  } finally {
    cancelling.value = null
  }
}

/** 行クリックで受信者パネルを開く/閉じる */
function onRowClick(notificationId: number) {
  selectedNotificationId.value =
    selectedNotificationId.value === notificationId ? null : notificationId
}

/** ステータスバッジの色を返す */
function getStatusSeverity(
  status: ConfirmableNotificationStatus,
): 'success' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'COMPLETED':
      return 'secondary'
    case 'EXPIRED':
      return 'warn'
    case 'CANCELLED':
      return 'danger'
    default:
      return 'secondary'
  }
}

/** 確認率（パーセント）を計算する */
function getConfirmationRate(notif: ConfirmableNotificationSummary): number {
  if (notif.totalRecipientCount === 0) return 0
  return Math.round((notif.confirmedCount / notif.totalRecipientCount) * 100)
}

onMounted(() => loadNotifications())
defineExpose({ refresh: () => loadNotifications() })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-base font-semibold text-surface-700">
        {{ $t('confirmable.history') }}
      </h3>
      <Button
        icon="pi pi-refresh"
        text
        size="small"
        :loading="loading"
        @click="loadNotifications"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading && notifications.length === 0" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <!-- 空状態 -->
    <div v-else-if="!loading && notifications.length === 0" class="py-12 text-center">
      <i class="pi pi-inbox mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">{{ $t('confirmable.no_history') }}</p>
    </div>

    <!-- 通知一覧 -->
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="notif in notifications"
        :key="notif.id"
        class="rounded-lg border border-surface-200 bg-white transition-shadow hover:shadow-sm"
      >
        <!-- 通知ヘッダー（クリックで受信者パネル開閉） -->
        <button
          class="w-full p-4 text-left"
          @click="onRowClick(notif.id)"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0 flex-1">
              <!-- タイトルとステータス -->
              <div class="mb-2 flex flex-wrap items-center gap-2">
                <span class="font-medium text-surface-800">{{ notif.title }}</span>
                <Tag
                  :value="$t(`confirmable.status.${notif.status}`)"
                  :severity="getStatusSeverity(notif.status)"
                />
                <Tag
                  :value="$t(`confirmable.priority.${notif.priority}`)"
                  :severity="notif.priority === 'URGENT' ? 'danger' : notif.priority === 'HIGH' ? 'warn' : 'secondary'"
                />
              </div>

              <!-- 確認率プログレスバー -->
              <div class="mb-2">
                <div class="mb-1 flex items-center justify-between text-xs text-surface-500">
                  <span>{{ $t('confirmable.confirmation_rate') }}</span>
                  <span>{{ notif.confirmedCount }} / {{ notif.totalRecipientCount }} ({{ getConfirmationRate(notif) }}%)</span>
                </div>
                <ProgressBar
                  :value="getConfirmationRate(notif)"
                  :show-value="false"
                  style="height: 6px"
                />
              </div>

              <!-- メタ情報 -->
              <div class="flex flex-wrap gap-3 text-xs text-surface-400">
                <span v-if="notif.deadlineAt">
                  <i class="pi pi-clock mr-1" />
                  {{ $t('confirmable.deadline') }}: {{ relativeTime(notif.deadlineAt) }}
                </span>
                <span>
                  <i class="pi pi-calendar mr-1" />
                  {{ relativeTime(notif.createdAt) }}
                </span>
              </div>
            </div>

            <!-- キャンセルボタン（ACTIVE の場合のみ） -->
            <div v-if="notif.status === 'ACTIVE'" class="shrink-0">
              <Button
                :label="$t('button.cancel')"
                size="small"
                severity="danger"
                text
                :loading="cancelling === notif.id"
                @click.stop="onCancel(notif.id)"
              />
            </div>
          </div>

          <!-- 展開インジケーター -->
          <div class="mt-2 flex items-center gap-1 text-xs text-surface-400">
            <i
              :class="selectedNotificationId === notif.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
            />
            <span>{{ $t('confirmable.recipients') }}</span>
          </div>
        </button>

        <!-- 受信者一覧（展開時） -->
        <div v-if="selectedNotificationId === notif.id" class="border-t border-surface-100">
          <ConfirmableNotificationRecipients
            :notification-id="notif.id"
            :scope-type="props.scopeType"
            :scope-id="props.scopeId"
          />
        </div>
      </div>
    </div>
  </div>
</template>
