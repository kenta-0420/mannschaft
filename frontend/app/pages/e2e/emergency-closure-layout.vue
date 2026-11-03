<script setup lang="ts">
/**
 * 検証専用ページ — 緊急休業画面の送信履歴・プレビュー表のレイアウトを E2E（DOM 寸法）で確かめる（CMP-261030-0651）。
 *
 * 開発・E2E 環境（import.meta.dev）でのみ有効。本番ビルドでは 404 を返す。
 * 認証不要・BE 不要（固定データを props で渡す）。画面固有の文言は持たない。
 */
import type { ClosureHistoryItem, ClosureConfirmationItem, ClosurePreviewItem } from '~/composables/useEmergencyClosureApi'

definePageMeta({ auth: false, layout: false })

if (!import.meta.dev) {
  throw createError({ statusCode: 404, fatal: true })
}

const REASON_200 = '院内設備の緊急点検および感染対策のため、'.repeat(11).slice(0, 200)

const historyItems: ClosureHistoryItem[] = [
  {
    id: 1,
    teamId: 1,
    startDate: '2026-11-10',
    endDate: '2026-11-12',
    startTime: '09:00',
    endTime: '12:00',
    reason: REASON_200,
    subject: '臨時休業のお知らせ',
    sentCount: 12,
    createdAt: '2026-11-03T09:00:00',
  },
  {
    id: 2,
    teamId: 1,
    startDate: '2026-12-01',
    endDate: '2026-12-01',
    startTime: null,
    endTime: null,
    reason: '設備点検',
    subject: '臨時休業のお知らせ',
    sentCount: 3,
    createdAt: '2026-11-03T10:00:00',
  },
]

const confirmations: ClosureConfirmationItem[] = [
  {
    userId: 1,
    userFullName: '山田 太郎',
    userEmail: 'u1@example.com',
    appointmentAt: '2026-11-10T09:30:00',
    confirmed: true,
    confirmedAt: '2026-11-03T10:00:00',
    reminderSent: true,
  },
  {
    userId: 2,
    userFullName: '鈴木 花子',
    userEmail: 'u2@example.com',
    appointmentAt: '2026-11-11T10:00:00',
    confirmed: false,
    confirmedAt: null,
    reminderSent: false,
  },
]

const previewItems: ClosurePreviewItem[] = [
  { reservationId: 1, userId: 1, userFullName: '山田 太郎', userEmail: 'u1@example.com', slotDate: '2026-11-10', startTime: '09:30:00', endTime: '10:00:00', status: 'CONFIRMED' },
  { reservationId: 2, userId: 2, userFullName: '鈴木 花子', userEmail: 'u2@example.com', slotDate: '2026-11-11', startTime: '10:00:00', endTime: '10:30:00', status: 'PENDING' },
]

const expandedClosureId = ref<number | null>(null)

function formatDate(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日(${'日月火水木金土'[d.getDay()]})`
}
function formatPreviewDateTime(slotDate: string, startTime: string, endTime: string): string {
  return `${formatDate(slotDate)} ${startTime.slice(0, 5)}〜${endTime.slice(0, 5)}`
}
function toggle(id: number) {
  expandedClosureId.value = expandedClosureId.value === id ? null : id
}
</script>

<template>
  <div class="mx-auto max-w-6xl space-y-4 p-4">
    <EmergencyClosureHistory
      data-testid="layout-history"
      :loading="false"
      :items="historyItems"
      :expanded-closure-id="expandedClosureId"
      :confirmations="confirmations"
      :confirmed-count="1"
      :total-count="2"
      :confirmations-loading="false"
      connection-state="LIVE"
      :format-date="formatDate"
      @toggle-confirmations="toggle"
    />
    <EmergencyClosurePreview
      data-testid="layout-preview"
      :loading="false"
      :done="true"
      :items="previewItems"
      :format-preview-date-time="formatPreviewDateTime"
      :status-label="(s: string) => s"
      :status-severity="() => 'info'"
    />
  </div>
</template>
