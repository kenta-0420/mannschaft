import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import SectionCard from '~/components/SectionCard.vue'
import ReservationsPage from './reservations.vue'

const { listMyReservations, showError } = vi.hoisted(() => ({
  listMyReservations: vi.fn(),
  showError: vi.fn(),
}))
mockNuxtImport('useReservationApi', () => () => ({ listMyReservations }))
mockNuxtImport('useNotification', () => () => ({ showError }))

const singleReservation = {
  id: 301,
  slot: { slotDate: '2026-07-10', startTime: '09:00', endTime: '09:30', lineName: '本人履歴の対象' },
  status: { status: 'CONFIRMED' },
  group: null,
}

async function mountPage() {
  const wrapper = await mountSuspended(ReservationsPage, {
    // 検査対象は本人履歴の描画。ガイド内の別機能はマウントしない。
    global: { stubs: { ReservationGuideModal: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  listMyReservations.mockReset()
  showError.mockReset()
})

describe('本人の予約履歴ページ', () => {
  it.each([
    { condition: 'slotがnull', slot: null },
    { condition: 'slot内の日時・対象名がnull', slot: { slotDate: null, startTime: null, endTime: null, lineName: null } },
  ])('AC-3: $conditionでも空欄ではなく日時と対象が不明であることを示す', async ({ slot }) => {
    listMyReservations.mockResolvedValue({ data: [{
      ...singleReservation, slot, status: { status: 'CANCELLED' },
    }] })

    const wrapper = await mountPage()
    const card = wrapper.findComponent(SectionCard)

    expect(card.get('h3').text()).toBe('Bookable item unavailable')
    expect(card.get('p').text()).toContain('Date unavailable')
    expect(card.get('p').text()).toContain('Time unavailable - Time unavailable')
    expect(card.text()).toContain('Cancelled')
    expect(showError).not.toHaveBeenCalled()
  })

  it('AC-2: グループ予約の終了時刻は単枠よりグループ末尾を優先する', async () => {
    listMyReservations.mockResolvedValue({ data: [{
      ...singleReservation,
      group: { groupId: 'history-group', groupSize: 3, groupEndTime: '11:00' },
      status: { status: 'COMPLETED' },
    }] })

    const wrapper = await mountPage()
    const card = wrapper.findComponent(SectionCard)

    expect(card.get('h3').text()).toBe('本人履歴の対象')
    expect(card.get('p').text()).toContain('2026-07-10 09:00 - 11:00')
    expect(card.get('p').text()).not.toContain('09:30')
    expect(card.text()).toContain('Completed')
  })

  it('AC-3: グループ末尾不明を単枠終了時刻で短縮せず不明と示す', async () => {
    listMyReservations.mockResolvedValue({ data: [{
      ...singleReservation,
      group: { groupId: 'partial-history-group', groupSize: 3, groupEndTime: null },
    }] })

    const wrapper = await mountPage()
    const text = wrapper.findComponent(SectionCard).get('p').text()

    expect(text).toContain('2026-07-10 09:00 - Time unavailable')
    expect(text).not.toContain('09:30')
  })

  it('正常な単枠予約の日時・対象名・状態表示を維持する', async () => {
    listMyReservations.mockResolvedValue({ data: [singleReservation] })

    const wrapper = await mountPage()
    const card = wrapper.findComponent(SectionCard)

    expect(listMyReservations).toHaveBeenCalledTimes(1)
    expect(card.get('h3').text()).toBe('本人履歴の対象')
    expect(card.get('p').text()).toContain('2026-07-10 09:00 - 09:30')
    expect(card.text()).toContain('Confirmed')
    expect(card.text()).not.toContain('unavailable')
  })

  it('履歴0件は通常の空表示を維持する', async () => {
    listMyReservations.mockResolvedValue({ data: [] })

    const wrapper = await mountPage()

    expect(wrapper.findAllComponents(SectionCard)).toHaveLength(0)
    expect(wrapper.text()).toContain('No reservations')
    expect(showError).not.toHaveBeenCalled()
  })
})
