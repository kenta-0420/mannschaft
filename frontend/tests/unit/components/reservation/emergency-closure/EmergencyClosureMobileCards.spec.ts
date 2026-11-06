import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmergencyClosureHistory from '~/components/reservation/emergency-closure/EmergencyClosureHistory.vue'
import EmergencyClosurePreview from '~/components/reservation/emergency-closure/EmergencyClosurePreview.vue'
import type { ClosureConfirmationItem, ClosureHistoryItem, ClosurePreviewItem } from '~/composables/useEmergencyClosureApi'

/**
 * 緊急休業 履歴・プレビューのモバイルカード表示（CMP-261104-0336）。
 * カード（md:hidden）と表（hidden md:block）が同じデータ・同じ操作を持つことを DOM で検証する。
 * 実寸法（390/767/768/1440）は E2E（tests/e2e/mobile/emergency-closure-layout.spec.ts）で測る。
 *   0336-5: カードと表で同データ・同操作
 *   0336-6: 空履歴・取得失敗・確認0件・長い理由/氏名・多数件
 */
const LONG = 'あ'.repeat(200)
const row = (id: number, over: Record<string, unknown> = {}) => ({
  id, teamId: 1, startDate: '2026-11-10', endDate: '2026-11-12', startTime: '09:00', endTime: '12:00',
  reason: `理由${id}`, subject: 's', messageBody: 'b', sentCount: 12 + id, cancelReservations: false,
  createdBy: 1, createdAt: '2026-11-03T09:00:00', ...over,
})
const conf = (userId: number, name: string, over: Record<string, unknown> = {}) => ({
  userId, userFullName: name, userEmail: 'u@example.com', appointmentAt: '2026-11-10T09:30:00',
  confirmed: true, confirmedAt: '2026-11-03T10:00:00', reminderSent: true, ...over,
})

function props(over: Record<string, unknown> = {}) {
  return {
    loading: false, items: [row(1)] as unknown as ClosureHistoryItem[], expandedClosureId: null,
    confirmations: [] as ClosureConfirmationItem[], confirmedCount: 0, totalCount: 0,
    confirmationsLoading: false, connectionState: 'LIVE' as const, ...over,
  }
}

describe('EmergencyClosureHistory カード/表の同値性', () => {
  it('0336-5: 期間・送信日時・理由・件数がカードと表の両方に同じ値で出る', async () => {
    const w = await mountSuspended(EmergencyClosureHistory, { props: props({ items: [row(1, { reason: LONG })] }) })
    const card = w.find('[data-testid="closure-history-card"]')
    const tds = w.findAll('tbody > tr:first-child > td')
    expect(card.find('[data-field="reason"]').text()).toBe(LONG)
    expect(tds[2]!.text()).toBe(LONG)
    expect(card.find('[data-field="count"]').text()).toBe(tds[3]!.text())
    expect(card.find('[data-field="sent-at"]').text()).toBe(tds[0]!.text())
    expect(card.find('[data-field="period"]').text()).toBe(tds[1]!.text())
    w.unmount()
  }, 120000)

  it('0336-5: 確認状況ボタンはカードでも表でも同じ操作（toggle-confirmations）を emit する', async () => {
    const w = await mountSuspended(EmergencyClosureHistory, { props: props({ items: [row(1), row(2)] }) })
    await w.find('[data-testid="closure-card-toggle"]').trigger('click')
    await w.find('tbody > tr:first-child button').trigger('click')
    expect(w.emitted('toggle-confirmations')).toEqual([[1], [1]])
    w.unmount()
  }, 120000)

  it('0336-2/5: 展開時の氏名・状態・予約日時・リマインドがカードと表で一致し、不明氏名・長い氏名も出る', async () => {
    const confs = [conf(1, '山田 太郎'), conf(2, '', { confirmed: false, reminderSent: false }), conf(3, LONG)]
    const w = await mountSuspended(EmergencyClosureHistory, {
      props: props({ expandedClosureId: 1, confirmations: confs, totalCount: 3, confirmedCount: 2 }),
    })
    const cards = w.findAll('[data-testid="closure-card-confirmation"]')
    const trs = w.findAll('table table tbody tr')
    expect(cards).toHaveLength(3)
    expect(trs).toHaveLength(3)
    cards.forEach((c, i) => {
      const tds = trs[i]!.findAll('td')
      expect(c.find('[data-field="name"]').text()).toBe(tds[0]!.text())
      expect(c.find('[data-field="appointment"]').text()).toBe(tds[1]!.text())
      expect(c.find('[data-field="status"]').text()).toBe(tds[2]!.text())
      expect(c.find('[data-field="reminder"]').text()).toBe(tds[3]!.text())
    })
    expect(cards[1]!.find('[data-field="name"]').text()).toBe('Unknown user')
    expect(cards[2]!.find('[data-field="name"]').text()).toBe(LONG)
    w.unmount()
  }, 120000)

  it('0336-6: 確認0件の展開でもカードの枠は出て項目は0件、undefined を含まない', async () => {
    const w = await mountSuspended(EmergencyClosureHistory, { props: props({ expandedClosureId: 1 }) })
    expect(w.find('[data-testid="closure-card-confirmations"]').exists()).toBe(true)
    expect(w.findAll('[data-testid="closure-card-confirmation"]')).toHaveLength(0)
    expect(w.text()).not.toContain('undefined')
    w.unmount()
  }, 120000)

  it('0336-6: 空履歴は空表示のみ・取得失敗はエラーのみでカードも表も出ない', async () => {
    const empty = await mountSuspended(EmergencyClosureHistory, { props: props({ items: [] }) })
    expect(empty.find('[data-testid="closure-history-cards"]').exists()).toBe(false)
    expect(empty.find('table').exists()).toBe(false)
    empty.unmount()
    const err = await mountSuspended(EmergencyClosureHistory, { props: props({ items: [], loadError: true }) })
    expect(err.find('[data-testid="closure-history-error"]').exists()).toBe(true)
    expect(err.find('[data-testid="closure-history-cards"]').exists()).toBe(false)
    err.unmount()
  }, 120000)

  it('0336-6: 多数件（50件）でカードと表の行数が一致する', async () => {
    const items = Array.from({ length: 50 }, (_, i) => row(i + 1))
    const w = await mountSuspended(EmergencyClosureHistory, { props: props({ items }) })
    expect(w.findAll('[data-testid="closure-history-card"]')).toHaveLength(50)
    expect(w.findAll('tbody > tr')).toHaveLength(50)
    w.unmount()
  }, 120000)

  it('0336-1/4: カードは md 未満のみ・表は md 以上のみ表示する CSS クラスを持つ', async () => {
    const w = await mountSuspended(EmergencyClosureHistory, { props: props() })
    expect(w.find('[data-testid="closure-history-cards"]').classes()).toContain('md:hidden')
    expect(w.find('table').element.parentElement!.className).toMatch(/hidden.*md:block/)
    expect(w.find('[data-testid="closure-card-toggle"]').classes()).toContain('min-h-11')
    w.unmount()
  }, 120000)
})

describe('EmergencyClosurePreview カード/表の同値性', () => {
  const item = (id: number, name: string) => ({
    reservationId: id, userId: id, userFullName: name, userEmail: 'u@example.com',
    slotDate: '2026-11-10', startTime: '09:30:00', endTime: '10:00:00', status: 'CONFIRMED',
  })
  const mount = (items: unknown[]) => mountSuspended(EmergencyClosurePreview, {
    props: {
      loading: false, done: true, items: items as unknown as ClosurePreviewItem[],
      formatPreviewDateTime: () => '11/10 09:30-10:00', statusLabel: (s: string) => `st:${s}`, statusSeverity: () => 'info',
    },
  })

  it('0336-3/5: 氏名・日時・状態がカードと表で一致する（空氏名は不明、長い氏名も保持）', async () => {
    const w = await mount([item(1, '山田 太郎'), item(2, ''), item(3, LONG)])
    const cards = w.findAll('[data-testid="closure-preview-card"]')
    const trs = w.findAll('tbody tr')
    expect(cards).toHaveLength(3)
    cards.forEach((c, i) => {
      const tds = trs[i]!.findAll('td')
      expect(c.find('[data-field="name"]').text()).toBe(tds[0]!.text())
      expect(c.find('[data-field="datetime"]').text()).toBe(tds[1]!.text())
      expect(c.text()).toContain('st:CONFIRMED')
    })
    expect(cards[1]!.find('[data-field="name"]').text()).toBe('Unknown user')
    expect(w.find('[data-testid="closure-preview-cards"]').classes()).toContain('md:hidden')
    w.unmount()
  }, 120000)

  it('0336-6: 0件はカードも表も出さない', async () => {
    const w = await mount([])
    expect(w.find('[data-testid="closure-preview-cards"]').exists()).toBe(false)
    expect(w.find('table').exists()).toBe(false)
    w.unmount()
  }, 120000)
})
