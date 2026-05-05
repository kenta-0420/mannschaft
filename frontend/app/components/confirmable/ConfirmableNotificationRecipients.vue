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
/** Accordion の開閉状態（初期 collapsed） */
const accordionValue = ref<string | null>(null)

/** 未確認者がいるか判定する（リマインド再送ボタンの表示制御用） */
const hasUnconfirmedRecipients = computed(() =>
  recipients.value.some(r => !r.isConfirmed && !r.excludedAt),
)

/** 未確認者数 */
const unconfirmedCount = computed(() =>
  recipients.value.filter(r => !r.isConfirmed && !r.excludedAt).length,
)

/**
 * MEMBER 視点（ALL_MEMBERS 公開）で受信者一覧を見ているかどうか判定する。
 * バックエンドは MEMBER 閲覧時に confirmedAt/confirmedVia/excludedAt を null にマスクし、
 * かつ未確認者のみ返却する設計のため、
 * 「全件 isConfirmed=false かつ confirmedAt=null かつ confirmedVia=null かつ excludedAt=null」
 * であれば MEMBER ビューと判定する（受信者が0件の場合は false）。
 */
const isMemberView = computed(() => {
  if (recipients.value.length === 0) return false
  return recipients.value.every(
    r => !r.isConfirmed && r.confirmedAt === null && r.confirmedVia === null && r.excludedAt === null
       && r.firstReminderSentAt === null && r.secondReminderSentAt === null,
  )
})

/** 受信者一覧を取得する */
async function loadRecipients() {
  loading.value = true
  try {
    const res = await getRecipients(props.scopeType, props.scopeId, props.notificationId)
    recipients.value = res.data
  } catch {
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
  } catch {
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
      <!-- リマインド再送ボタン（ADMIN ビューかつ未確認者がいる時のみ） -->
      <div v-if="!isMemberView && hasUnconfirmedRecipients" class="mb-3 flex justify-end">
        <Button
          :label="$t('confirmable.resend_reminder')"
          icon="pi pi-send"
          size="small"
          severity="secondary"
          :loading="resending"
          @click="onResendReminder"
        />
      </div>

      <!-- 折り畳み Accordion（初期 collapsed） -->
      <Accordion v-model:value="accordionValue">
        <AccordionPanel value="recipients">
          <AccordionHeader>
            <div class="flex w-full items-center justify-between gap-2">
              <span class="text-sm font-medium text-surface-700">
                <template v-if="isMemberView">
                  {{ $t('confirmable.unconfirmed_list') }}
                </template>
                <template v-else>
                  {{ $t('confirmable.recipients') }}
                </template>
              </span>
              <span class="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                {{ $t('confirmable.unconfirmed_count', { count: unconfirmedCount }) }}
              </span>
            </div>
          </AccordionHeader>
          <AccordionContent>
            <!-- MEMBER ビュー: 未確認者一覧のみ簡素表示 -->
            <DataTable v-if="isMemberView" :value="recipients" size="small" striped-rows>
              <Column field="userId" header="User ID" style="width: 80px" />
              <Column :header="$t('confirmable.confirmed')">
                <template #body>
                  <Tag value="未確認" severity="warn" />
                </template>
              </Column>
            </DataTable>

            <!-- ADMIN ビュー: 完全な受信者テーブル -->
            <DataTable v-else :value="recipients" size="small" striped-rows>
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
          </AccordionContent>
        </AccordionPanel>
      </Accordion>
    </template>
  </div>
</template>
