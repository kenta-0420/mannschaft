// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { onMounted, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { NotificationResponse } from '~/types/notification'
import NotificationList from '~/components/notification/NotificationList.vue'

type ConfirmableNotificationFixture = NotificationResponse & { isConfirmed?: boolean | null }

const mocks = vi.hoisted(() => ({
  notificationApi: {
    getNotifications: vi.fn(),
    markAsRead: vi.fn(),
    markAsUnread: vi.fn(),
    markAllAsRead: vi.fn(),
    snooze: vi.fn(),
  },
  confirmableApi: {
    confirmNotification: vi.fn(),
    confirmPersonalNotification: vi.fn(),
    getNotificationDetail: vi.fn(),
  },
  showError: vi.fn(),
  toastAdd: vi.fn(),
  routerPush: vi.fn(),
}))

vi.mock('~/composables/useNotificationApi', () => ({
  useNotificationApi: () => mocks.notificationApi,
}))
vi.mock('~/composables/useConfirmableNotificationApi', () => ({
  useConfirmableNotificationApi: () => mocks.confirmableApi,
}))
vi.mock('~/composables/useEmergencyClosureApi', () => ({
  useEmergencyClosureApi: () => ({ confirmClosure: vi.fn() }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ showError: mocks.showError }),
}))
vi.mock('~/composables/useRelativeTime', () => ({
  useRelativeTime: () => ({ relativeTime: () => '今' }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { timezone: 'Asia/Tokyo' } }),
}))
vi.mock('nuxt/app', async () => {
  const actual = await vi.importActual<typeof import('nuxt/app')>('nuxt/app')
  return {
    ...actual,
    useRouter: () => ({ push: mocks.routerPush }),
    useState: (_key: string, init: () => unknown) => ref(init()),
  }
})
vi.mock('#app/composables/router', async () => {
  const actual =
    await vi.importActual<typeof import('#app/composables/router')>('#app/composables/router')
  return { ...actual, useRouter: () => ({ push: mocks.routerPush }) }
})
vi.mock('#app/composables/state', async () => {
  const actual =
    await vi.importActual<typeof import('#app/composables/state')>('#app/composables/state')
  return { ...actual, useState: (_key: string, init: () => unknown) => ref(init()) }
})
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: mocks.toastAdd }) }))

vi.mock('#imports', () => ({
  ref,
  watch,
  onMounted,
  useState: (_key: string, init: () => unknown) => ref(init()),
  useNotificationApi: () => mocks.notificationApi,
  useConfirmableNotificationApi: () => mocks.confirmableApi,
  useEmergencyClosureApi: () => ({ confirmClosure: vi.fn() }),
  useNotification: () => ({ showError: mocks.showError }),
  useI18n: () => ({ t: (key: string) => key }),
  useRouter: () => ({ push: mocks.routerPush }),
  useRelativeTime: () => ({ relativeTime: () => '今' }),
  useAuthStore: () => ({ user: { timezone: 'Asia/Tokyo' } }),
  useToast: () => ({ add: mocks.toastAdd }),
}))

const confirmButtonLabel = 'confirmable.confirm_button'
const confirmedLabel = 'confirmable.already_confirmed'

const notification = (
  isConfirmed: boolean | null,
  isRead: boolean,
): ConfirmableNotificationFixture => ({
  id: 3255966,
  notificationType: 'RECRUITMENT_PENALTY_APPLIED',
  priority: 'URGENT',
  title: '確認状態のテスト通知',
  body: '確認状態の独立性を検証する',
  sourceType: 'CONFIRMABLE_NOTIFICATION',
  sourceId: 67,
  scopeType: 'TEAM',
  scopeId: 'team-w16-test',
  scopeName: '試験チーム',
  actionUrl: '/notifications',
  actor: null,
  isRead,
  readAt: isRead ? '2026-09-28T10:00:00' : null,
  snoozedUntil: null,
  createdAt: '2026-09-28T09:00:00',
  isConfirmed,
})

const stubs = {
  Button: {
    props: ['label'],
    emits: ['click'],
    template:
      '<button type="button" :data-label="label" :data-testid="label === \'\\u3059\\u3079\\u3066\\u65E2\\u8AAD\\u306B\\u3059\\u308B\' ? \'mark-all-read\' : undefined" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  SelectButton: { template: '<div />' },
  Menu: { template: '<div />' },
  LoadingBounce: { template: '<div />' },
}

const i18n = createI18n({
  legacy: false,
  locale: 'ja',
  messages: { ja: {}, en: {}, zh: {}, ko: {}, es: {}, de: {} },
})
const router = createRouter({ history: createMemoryHistory(), routes: [] })

async function mountList(row: ConfirmableNotificationFixture) {
  mocks.notificationApi.getNotifications.mockResolvedValue({
    data: [row],
    meta: { total: 1, page: 0, size: 20, totalPages: 1 },
  })
  mocks.confirmableApi.getNotificationDetail.mockResolvedValue({
    data: { totalRecipientCount: 1, confirmedCount: 0, unconfirmedVisibility: 'ALL_MEMBERS' },
  })
  return mount(NotificationList, {
    global: {
      stubs,
      plugins: [i18n, router],
      mocks: { $t: (key: string) => key },
    },
  })
}

describe('通知の既読と確認状態', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.notificationApi.markAsRead.mockResolvedValue(undefined)
    mocks.notificationApi.markAsUnread.mockResolvedValue(undefined)
    mocks.notificationApi.markAllAsRead.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmNotification.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmPersonalNotification.mockResolvedValue(undefined)
  })

  it('既読状態にかかわらず未確認だけ確認ボタン、確認済みだけ確認済み表示を出す', async () => {
    const unconfirmedRead = await mountList(notification(false, true))
    await flushPromises()
    expect(unconfirmedRead.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    unconfirmedRead.unmount()

    const confirmedUnread = await mountList(notification(true, false))
    await flushPromises()
    expect(confirmedUnread.text()).toContain(confirmedLabel)
    expect(confirmedUnread.text()).not.toContain(confirmButtonLabel)
    confirmedUnread.unmount()

    const notConfirmable = await mountList(notification(null, false))
    await flushPromises()
    expect(notConfirmable.text()).not.toContain(confirmButtonLabel)
    expect(notConfirmable.text()).not.toContain(confirmedLabel)
    notConfirmable.unmount()
  })

  it('タイトル既読・既読切替・全件既読で確認状態を変えず、確認API成功だけが確認済みにする', async () => {
    const row = notification(false, false)
    const wrapper = await mountList(row)
    await flushPromises()

    const rowElement = wrapper.get('[role="button"]')
    await rowElement.trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    expect(row.isConfirmed).toBe(false)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await rowElement.get('button[title]:not([aria-label])').trigger('click')
    await flushPromises()
    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await wrapper.get('[data-testid="mark-all-read"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).toContain(confirmButtonLabel)

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()
    expect(mocks.confirmableApi.confirmNotification).toHaveBeenCalledWith(
      'TEAM',
      'team-w16-test',
      67,
    )
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('確認済み通知を未読にしても確認済み表示を維持する', async () => {
    const wrapper = await mountList(notification(true, false))
    await flushPromises()
    const rowElement = wrapper.get('[role="button"]')
    expect(wrapper.text()).toContain(confirmedLabel)

    await rowElement.get('button[title]:not([aria-label])').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)

    await rowElement.get('button[title]:not([aria-label])').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsUnread).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    wrapper.unmount()
  })

  it('確認後の既読更新に失敗しても確認済み状態を保ち、確認失敗とは表示しない', async () => {
    mocks.notificationApi.markAsRead.mockRejectedValue(new Error('read failed'))
    const wrapper = await mountList(notification(false, false))
    await flushPromises()

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()

    expect(mocks.confirmableApi.confirmNotification).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    expect(mocks.showError).toHaveBeenCalledWith('inbox.action.readFailed')
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('確認APIに失敗した場合は未確認のまま確認ボタンを残す', async () => {
    mocks.confirmableApi.confirmNotification.mockRejectedValue(new Error('confirm failed'))
    const wrapper = await mountList(notification(false, true))
    await flushPromises()

    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.findAll(`button[data-label="${confirmButtonLabel}"]`)).toHaveLength(1)
    expect(wrapper.text()).not.toContain(confirmedLabel)
    expect(mocks.notificationApi.markAsRead).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
