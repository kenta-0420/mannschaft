<script setup lang="ts">
import type { ConfirmableNotificationRecipientItem } from '~/types/confirmable'

const props = defineProps<{
  notificationId: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const { getRecipients, resendReminder } = useConfirmableNotificationApi()
const { showError } = useNotification()
const { t } = useI18n()
const { relativeTime } = useRelativeTime()

const recipients = ref<ConfirmableNotificationRecipientItem[]>([])
const loading = ref(false)
const resending = ref(false)

/** 未確認者がいるか判定する（リマインド再送ボタンの表示制御用） */
const hasUnconfirmedRecipients = computed(() =>
  recipients.value.some(r => !r.isConfirmed && !r.excludedAt),
)

/** 受信者一覧を取得する */
async function loadRecipients() {
  loading.value = true
  try {
    const res = await getRecipients(props.scopeType, props.scopeId, props.notificationId)
    recipients.value = res.data
  } catch (err) {
    console.error('受信者一覧の取得に失敗しました', err)
    showError(t('confirmable.load_recipients_error'))
  } finally {
    loading.value = false
  }
}

/** リマインダーを再送する */
async function onResendReminder() {
  resending.value = true
  try {
    await resendReminder(props.scopeType, props.scopeId, props.notificationId)
    const toast = useToast()
    toast.add({ severity: 'success', summary: t('dialog.success'), life: 3000 })
  } catch (err) {
    console.error('リマインダー再送に失敗しました', err)
    showError(t('confirmable.resend_error'))
  } finally {
    resending.value = false
  }
}

onMounted(() => loadRecipients())
</script>

<template>
  <div class="p-4">
    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>

    <template v-else>
      <!-- リマインド再送ボタン -->
      <div v-if="hasUnconfirmedRecipients" class="mb-3 flex justify-end">
        <Button
          :label="$t('confirmable.resend_reminder')"
          icon="pi pi-send"
          size="small"
          severity="secondary"
          :loading="resending"
          @click="onResendReminder"
        />
      </div>

      <!-- 受信者テーブル -->
      <DataTable :value="recipients" size="small" striped-rows>
        <Column field="userId" header="User ID" style="width: 80px" />

        <!-- 確認ステータス -->
        <Column :header="$t('confirmable.confirmed')">
          <template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">
            <Tag
              v-if="data.isConfirmed"
              :value="$t('confirmable.already_confirmed')"
              severity="success"
            />
            <Tag
              v-else-if="data.excludedAt"
              value="除外"
              severity="secondary"
            />
            <Tag
              v-else
              value="未確認"
              severity="warn"
            />
          </template>
        </Column>

        <!-- 確認日時 -->
        <Column :header="$t('confirmable.confirmed_at')">
          <template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">
            <span v-if="data.confirmedAt" class="text-xs text-surface-600">
              {{ relativeTime(data.confirmedAt) }}
            </span>
            <span v-else class="text-xs text-surface-300">—</span>
          </template>
        </Column>

        <!-- 確認方法 -->
        <Column :header="$t('confirmable.confirmed_via_label')">
          <template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">
            <span v-if="data.confirmedVia" class="text-xs">
              {{ $t(`confirmable.confirmed_via.${data.confirmedVia}`) }}
            </span>
            <span v-else class="text-xs text-surface-300">—</span>
          </template>
        </Column>

        <!-- 1回目リマインド送信日時 -->
        <Column :header="$t('confirmable.first_reminder_sent')">
          <template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">
            <span v-if="data.firstReminderSentAt" class="text-xs text-surface-600">
              {{ relativeTime(data.firstReminderSentAt) }}
            </span>
            <span v-else class="text-xs text-surface-300">—</span>
          </template>
        </Column>

        <!-- 2回目リマインド送信日時 -->
        <Column :header="$t('confirmable.second_reminder_sent')">
          <template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">
            <span v-if="data.secondReminderSentAt" class="text-xs text-surface-600">
              {{ relativeTime(data.secondReminderSentAt) }}
            </span>
            <span v-else class="text-xs text-surface-300">—</span>
          </template>
        </Column>
      </DataTable>
    </template>
  </div>
</template>
