<script setup lang="ts">
import type {
  MembershipSubscriptionListItem,
  MembershipSubscriptionStatus,
} from '~/types/membershipSubscription'

/**
 * F08.9 P5 継続課金管理画面（払い手向け・設計書 04 §2）。
 *
 * 一覧・解約（○月○日まで利用可・日割り返金なしを明示）・今月スキップ（次回課金日明示）・再開。
 * 加入（subscribe）UI は Stripe.js confirm フローを要するため本 PR の範囲外（次 PR・設計書 04 §2 参照）。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = useMembershipSubscriptionApi()
const notification = useNotification()
const { formatDate } = useDatetime()

const subscriptions = ref<MembershipSubscriptionListItem[]>([])
const loading = ref(false)

// 確認ダイアログの種別。
type DialogAction = 'cancel' | 'skip' | 'resume'
const dialogVisible = ref(false)
const dialogAction = ref<DialogAction | null>(null)
const target = ref<MembershipSubscriptionListItem | null>(null)
const processing = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.listMySubscriptions()
    subscriptions.value = res.data
  } catch {
    notification.error(t('payment.membership.subscription.loadError'))
  } finally {
    loading.value = false
  }
}

/** 状態バッジの色（色以外に記号併用のためアイコンも別途付与）。 */
function statusSeverity(
  status: MembershipSubscriptionStatus | null,
): 'success' | 'warn' | 'danger' | 'secondary' | 'info' {
  const map: Record<MembershipSubscriptionStatus, 'success' | 'warn' | 'danger' | 'secondary' | 'info'> = {
    PENDING: 'info',
    ACTIVE: 'success',
    PAST_DUE: 'danger',
    CANCELLED: 'secondary',
    EXPIRED: 'secondary',
  }
  return status ? map[status] : 'secondary'
}

/** 課金周期ラベル。 */
function intervalLabel(sub: MembershipSubscriptionListItem): string {
  if (sub.billingInterval === 'YEARLY') return t('payment.membership.subscription.yearly')
  return t('payment.membership.subscription.monthly')
}

/** 額面表示（通貨記号は通貨コード併記で簡潔に）。 */
function amountLabel(sub: MembershipSubscriptionListItem): string {
  if (sub.faceAmount === null) return ''
  const currency = sub.currency ?? 'JPY'
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(sub.faceAmount)
}

function openDialog(action: DialogAction, sub: MembershipSubscriptionListItem) {
  dialogAction.value = action
  target.value = sub
  dialogVisible.value = true
}

/** ダイアログのタイトル。 */
const dialogTitle = computed(() => {
  switch (dialogAction.value) {
    case 'cancel':
      return t('payment.membership.subscription.cancelAtPeriodEnd')
    case 'skip':
      return t('payment.membership.subscription.skip')
    case 'resume':
      return t('payment.membership.subscription.resume')
    default:
      return ''
  }
})

/** ダイアログ本文（日付埋め込み）。 */
const dialogMessage = computed(() => {
  const sub = target.value
  if (!sub) return ''
  switch (dialogAction.value) {
    case 'cancel':
      return t('payment.membership.subscription.cancelConfirm', {
        date: formatDate(sub.validUntil) || '-',
      })
    case 'skip':
      return t('payment.membership.subscription.skipConfirm', {
        date: formatDate(sub.nextBillingDate) || '-',
      })
    case 'resume':
      return t('payment.membership.subscription.resumeConfirm')
    default:
      return ''
  }
})

async function confirmAction() {
  const sub = target.value
  const action = dialogAction.value
  if (!sub || !action) return
  processing.value = true
  try {
    if (action === 'cancel') {
      await api.cancelSubscription(sub.id)
      notification.success(t('payment.membership.subscription.cancelSuccess'))
    } else if (action === 'skip') {
      await api.skipSubscription(sub.id)
      notification.success(t('payment.membership.subscription.skipSuccess'))
    } else {
      await api.resumeSubscription(sub.id)
      notification.success(t('payment.membership.subscription.resumeSuccess'))
    }
    dialogVisible.value = false
    await load()
  } catch {
    notification.error(t('payment.membership.subscription.actionError'))
  } finally {
    processing.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="$t('payment.membership.subscription.title')" class="mb-4" />

    <div v-if="loading" class="flex justify-center p-8">
      <LoadingBounce />
    </div>

    <template v-else>
      <div
        v-if="subscriptions.length === 0"
        class="rounded border border-dashed p-8 text-center text-surface-400"
      >
        {{ $t('payment.membership.subscription.empty') }}
      </div>

      <div v-else class="flex flex-col gap-3">
        <div
          v-for="sub in subscriptions"
          :key="sub.id"
          class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
        >
          <div class="flex items-start justify-between gap-2">
            <div>
              <div class="font-semibold">
                {{ sub.itemName || $t('payment.membership.subscription.unnamedItem') }}
              </div>
              <div class="mt-1 flex flex-wrap items-center gap-2 text-sm text-surface-500">
                <span>{{ intervalLabel(sub) }}</span>
                <span aria-hidden="true">·</span>
                <span>{{ amountLabel(sub) }}</span>
                <template v-if="sub.beneficiaryDisplayName">
                  <span aria-hidden="true">·</span>
                  <span>
                    {{ $t('payment.membership.subscription.beneficiary') }}:
                    {{ sub.beneficiaryDisplayName }}
                  </span>
                </template>
              </div>
            </div>
            <Tag
              :value="$t(`payment.membership.subscription.status.${sub.status}`)"
              :severity="statusSeverity(sub.status)"
              rounded
            />
          </div>

          <!-- 次回課金日・利用期限（常時表示） -->
          <div class="mt-3 grid grid-cols-1 gap-1 text-sm text-surface-600 dark:text-surface-300 sm:grid-cols-2">
            <div v-if="sub.nextBillingDate">
              <i class="pi pi-calendar mr-1" aria-hidden="true" />
              {{ $t('payment.membership.subscription.nextBilling') }}:
              {{ formatDate(sub.nextBillingDate) }}
            </div>
            <div v-if="sub.validUntil">
              <i class="pi pi-clock mr-1" aria-hidden="true" />
              {{ $t('payment.membership.subscription.validUntil') }}:
              {{ formatDate(sub.validUntil) }}
            </div>
          </div>

          <!-- スキップ中の案内（再開予定日） -->
          <Message
            v-if="sub.skipUntil"
            severity="info"
            :closable="false"
            class="mt-3"
          >
            {{ $t('payment.membership.subscription.skipped', { date: formatDate(sub.skipUntil) }) }}
          </Message>

          <!-- 解約予約中の案内（○月○日まで利用可） -->
          <Message
            v-else-if="sub.cancelAtPeriodEnd"
            severity="warn"
            :closable="false"
            class="mt-3"
          >
            {{ $t('payment.membership.subscription.cancelScheduled', { date: formatDate(sub.validUntil) || '-' }) }}
          </Message>

          <!-- 支払い失敗（PAST_DUE）の警告＋カード更新導線（文言のみ） -->
          <Message
            v-if="sub.status === 'PAST_DUE'"
            severity="error"
            :closable="false"
            class="mt-3"
          >
            <div>{{ $t('payment.membership.subscription.pastDue') }}</div>
            <div class="mt-1 text-sm">
              {{ $t('payment.membership.subscription.pastDueGuide') }}
            </div>
          </Message>

          <!-- 操作ボタン -->
          <div class="mt-3 flex flex-wrap gap-2">
            <!-- 再開（スキップ中のみ） -->
            <Button
              v-if="sub.skipUntil"
              :label="$t('payment.membership.subscription.resume')"
              icon="pi pi-play"
              size="small"
              @click="openDialog('resume', sub)"
            />
            <!-- 今月スキップ（スキップ中・解約予約中・終了状態でない場合） -->
            <Button
              v-else-if="
                !sub.cancelAtPeriodEnd &&
                sub.status !== 'CANCELLED' &&
                sub.status !== 'EXPIRED'
              "
              :label="$t('payment.membership.subscription.skip')"
              icon="pi pi-pause"
              size="small"
              severity="secondary"
              @click="openDialog('skip', sub)"
            />
            <!-- 解約（解約予約中・終了状態でない場合） -->
            <Button
              v-if="
                !sub.cancelAtPeriodEnd &&
                sub.status !== 'CANCELLED' &&
                sub.status !== 'EXPIRED'
              "
              :label="$t('payment.membership.subscription.cancel')"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              @click="openDialog('cancel', sub)"
            />
          </div>
        </div>
      </div>
    </template>

    <!-- 確認ダイアログ（誤操作防止・解約/スキップ/再開で共用） -->
    <Dialog
      v-model:visible="dialogVisible"
      :header="dialogTitle"
      modal
      :style="{ width: '420px' }"
    >
      <p class="whitespace-pre-line text-sm text-surface-600 dark:text-surface-400">
        {{ dialogMessage }}
      </p>
      <template #footer>
        <Button
          :label="$t('common.button.cancel')"
          severity="secondary"
          @click="dialogVisible = false"
        />
        <Button
          :label="$t('payment.membership.subscription.confirmAction')"
          :severity="dialogAction === 'cancel' ? 'danger' : 'primary'"
          :loading="processing"
          @click="confirmAction"
        />
      </template>
    </Dialog>
  </div>
</template>
