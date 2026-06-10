<script setup lang="ts">
import type { MyTournamentFeeItem } from '~/types/tournament'

/**
 * F08.7.1: 大会参加費 Connect 決済ページ。
 *
 * 自分が対象の大会参加費を一覧表示し、未払い項目を
 * チェックアウト（Connect 決済）できる。
 *
 * フロー:
 *   ① 参加費一覧取得（GET /api/v1/tournament-fees/my）
 *   ② 未払い項目の「今すぐ支払う」ボタンをクリック
 *   ③ チェックアウト実行（POST /api/v1/tournament-fees/{feeId}/checkout）
 *   ④ 結果通知 + 一覧リロード
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { getMyTournamentFees, checkoutFee } = useTournamentFeeApi()
const notification = useNotification()
const { formatDate, formatDateTime } = useDatetime()

useHead({ title: t('tournamentFee.pageTitle') })

// ── 状態 ──────────────────────────────────────────────────────

const fees = ref<MyTournamentFeeItem[]>([])
const loading = ref(true)
const loadError = ref(false)

/** 処理中の feeId（重複送信防止） */
const processing = ref<string | null>(null)

// ── データ取得 ─────────────────────────────────────────────────

async function loadFees() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getMyTournamentFees()
    fees.value = res.data.fees
  } catch {
    loadError.value = true
    notification.error(t('common.loadError'))
  } finally {
    loading.value = false
  }
}

// ── チェックアウト ────────────────────────────────────────────

async function handleCheckout(fee: MyTournamentFeeItem) {
  if (processing.value) return
  processing.value = fee.feeId
  try {
    await checkoutFee(fee.feeId)
    notification.success(t('tournamentFee.checkoutSuccess'))
    await loadFees()
  } catch {
    notification.error(t('tournamentFee.checkoutError'))
  } finally {
    processing.value = null
  }
}

// ── 初期化 ────────────────────────────────────────────────────

onMounted(loadFees)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">{{ t('tournamentFee.pageTitle') }}</h1>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <!-- エラー -->
    <div
      v-else-if="loadError"
      class="rounded-lg bg-red-50 border border-red-200 px-6 py-6 text-center text-red-600"
    >
      {{ t('common.loadError') }}
    </div>

    <!-- 参加費なし -->
    <div
      v-else-if="fees.length === 0"
      class="rounded-lg bg-surface-50 border border-surface-200 px-6 py-10 text-center text-surface-500"
    >
      {{ t('tournamentFee.noFees') }}
    </div>

    <!-- 参加費一覧 -->
    <div v-else class="flex flex-col gap-4">
      <div
        v-for="fee in fees"
        :key="fee.feeId"
        class="rounded-xl border border-surface-200 bg-surface-0 px-4 py-4 shadow-sm"
      >
        <div class="flex items-start justify-between gap-4">
          <!-- 参加費情報 -->
          <div class="flex-1 min-w-0">
            <p class="font-semibold text-surface-800 truncate">{{ fee.tournamentName }}</p>
            <p v-if="fee.divisionName" class="text-sm text-surface-500">{{ fee.divisionName }}</p>
            <p class="text-surface-700 mt-1">{{ fee.title }}</p>
            <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-surface-500">
              <span>
                {{ t('tournamentFee.faceAmount') }}: ¥{{ fee.faceAmount.toLocaleString() }}
              </span>
              <span class="font-medium text-surface-700">
                {{ t('tournamentFee.totalCharge') }}: ¥{{ fee.totalCharge.toLocaleString() }}
              </span>
              <span v-if="fee.dueDate">
                {{ t('tournamentFee.dueDate') }}: {{ formatDate(fee.dueDate) }}
              </span>
              <span v-if="fee.alreadyPaid && fee.paidAt">
                {{ t('tournamentFee.paidAt') }}: {{ formatDateTime(fee.paidAt) }}
              </span>
            </div>
          </div>

          <!-- 支払い状態 / ボタン -->
          <div class="flex-shrink-0">
            <Tag
              v-if="fee.alreadyPaid"
              :value="t('tournamentFee.paid')"
              severity="success"
            />
            <Button
              v-else
              :label="t('tournamentFee.payNow')"
              :loading="processing === fee.feeId"
              :disabled="processing !== null"
              size="small"
              @click="handleCheckout(fee)"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
