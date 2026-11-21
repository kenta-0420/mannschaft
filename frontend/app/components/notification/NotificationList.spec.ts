// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import type { NotificationResponse, NotificationListResponse } from '~/types/notification'
import NotificationList from '~/components/notification/NotificationList.vue'

// happy-dom単独mountでも、通常のapp/plugins/dayjs.tsと同じ日時前提を満たす。
dayjs.extend(utc)
dayjs.extend(timezone)

type ConfirmableNotificationFixture = NotificationResponse & { isConfirmed?: boolean | null }

const mocks = vi.hoisted(() => ({
  authStore: {
    user: { id: 42, timezone: 'Asia/Tokyo' } as { id: number; timezone: string } | null,
  },
  notificationStore: { latestNotification: null as { id: number; userId: number } | null },
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
  confirmClosure: vi.fn(),
  captureQuiet: vi.fn(),
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
  useEmergencyClosureApi: () => ({ confirmClosure: mocks.confirmClosure }),
}))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ showError: mocks.showError }),
}))
vi.mock('~/composables/useRelativeTime', () => ({
  useRelativeTime: () => ({ relativeTime: () => '今' }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => mocks.authStore,
}))
vi.mock('~/stores/useNotificationStore', () => ({
  useNotificationStore: () => mocks.notificationStore,
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
  onUnmounted,
  useState: (_key: string, init: () => unknown) => ref(init()),
  useNotificationApi: () => mocks.notificationApi,
  useConfirmableNotificationApi: () => mocks.confirmableApi,
  useEmergencyClosureApi: () => ({ confirmClosure: mocks.confirmClosure }),
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
  useNotification: () => ({ showError: mocks.showError }),
  useI18n: () => ({ t: (key: string) => key }),
  useRouter: () => ({ push: mocks.routerPush }),
  useRelativeTime: () => ({ relativeTime: () => '今' }),
  useAuthStore: () => mocks.authStore,
  useNotificationStore: () => mocks.notificationStore,
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
      '<button type="button" :data-label="label" :data-testid="label === \'notification.list.markAllRead\' ? \'mark-all-read\' : undefined" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  SelectButton: { props: ['modelValue'], emits: ['update:modelValue'], template: '<div />' },
  Menu: { props: ['model'], template: '<div />' },
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

function listResponse(
  rows: NotificationResponse[],
  page = 0,
  totalPages = 1,
): NotificationListResponse {
  return { data: rows, meta: { total: totalPages * 20, page, size: 20, totalPages } }
}
function liveRow(id: number): NotificationResponse {
  return {
    ...notification(null, false),
    id,
    title: `通知 ${id}`,
    sourceType: 'SYSTEM',
    sourceId: null,
  }
}
async function receiveNotification(id: number) {
  mocks.notificationStore.latestNotification = { id, userId: mocks.authStore.user?.id ?? 42 }
  await flushPromises()
}

describe('N3–N5: 通知一覧のリアルタイム取得境界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.notificationApi.getNotifications.mockReset()
    mocks.authStore = reactive({ user: { id: 42, timezone: 'Asia/Tokyo' } })
    mocks.notificationStore = reactive({ latestNotification: null })
    mocks.notificationApi.markAsRead.mockResolvedValue(undefined)
    mocks.confirmableApi.confirmNotification.mockResolvedValue(undefined)
    mocks.notificationApi.markAllAsRead.mockResolvedValue(undefined)
    mocks.notificationApi.snooze.mockReset().mockResolvedValue(undefined)
  })

  it('N3: 空一覧でも受信後に正規APIを再取得し、新しい通知を表示する', async () => {
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([]))
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(2), liveRow(1)]))
    await receiveNotification(2)
    expect(wrapper.text()).toContain('通知 2')
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N3: 未読filterを保持して受信を取り込み、同idを重複表示しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    wrapper.findComponent(stubs.SelectButton).vm.$emit('update:modelValue', 'unread')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(2), liveRow(2)]))
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({
      page: 0,
      size: 20,
      isRead: false,
    })
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N4: 取得済みpage深さを維持し、refresh後のloadMoreでも重複しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([liveRow(1)], 0, 3))
    await (wrapper.vm as unknown as { refresh: () => Promise<void> }).refresh()
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(listResponse([liveRow(2)], 1, 3))
    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()
    mocks.notificationApi.getNotifications
      .mockResolvedValueOnce(listResponse([liveRow(3), liveRow(1)], 0, 3))
      .mockResolvedValueOnce(listResponse([liveRow(2)], 1, 3))
    await receiveNotification(3)
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).toContain('通知 2')
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(
      listResponse([liveRow(2), liveRow(4)], 2, 3),
    )
    await wrapper.get('button[data-label="notification.list.loadMore"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({
      page: 2,
      size: 20,
      isRead: undefined,
    })
    expect(wrapper.findAll('[data-notification-id="2"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('N5: 取得中の連続通知を次のrefresh一回へ集約する', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          complete = resolve
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    for (let id = 3; id <= 12; id++) await receiveNotification(id)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(12)]))
    complete(listResponse([liveRow(2)]))
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('通知 12')
    wrapper.unmount()
  })

  it('N5: unmount後の応答からサマリ取得や追加APIを発行しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          complete = resolve
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    complete(listResponse([notification(false, false)]))
    await flushPromises()
    expect(mocks.confirmableApi.getNotificationDetail).not.toHaveBeenCalled()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
  })

  it('N3: 確認成功前に開始したGETの遅着で確認状態を巻き戻さない', async () => {
    const wrapper = await mountList(notification(false, false))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          complete = resolve
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(
      listResponse([notification(true, true)]),
    )
    complete(listResponse([notification(false, false)]))
    await flushPromises()
    expect(wrapper.text()).toContain(confirmedLabel)
    expect(wrapper.text()).not.toContain(confirmButtonLabel)
    wrapper.unmount()
  })

  it('N2: A確認の遅着後にB資格で既読POSTを追送しない', async () => {
    const wrapper = await mountList(notification(false, false))
    await flushPromises()
    let complete!: () => void
    mocks.confirmableApi.confirmNotification.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve
        }),
    )
    await wrapper.get(`button[data-label="${confirmButtonLabel}"]`).trigger('click')
    mocks.authStore.user = { id: 43, timezone: 'Asia/Tokyo' }
    complete()
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).not.toHaveBeenCalled()
    expect(mocks.toastAdd).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('N5追加: 既読toggle成功前に取得した旧GETで未読へ戻らない', async () => {
    const stale = liveRow(1)
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let completeOld!: (value: NotificationListResponse) => void
    let completeFresh!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeFresh = resolve
          }),
      )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    await wrapper.get('button[aria-label="notification.list.markRead"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAsRead).toHaveBeenCalledWith(1)
    expect(stale.isRead).toBe(false)
    completeOld(listResponse([stale]))
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)
    expect(wrapper.find('button[aria-label="notification.list.markRead"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="notification.list.markUnread"]').exists()).toBe(true)
    completeFresh(listResponse([{ ...liveRow(1), isRead: true }]))
    await flushPromises()
    expect(wrapper.find('button[aria-label="notification.list.markUnread"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('N5追加: 全件既読成功前に取得した旧GETで全件を未読へ戻さない', async () => {
    const stale = [liveRow(1), liveRow(2)]
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValueOnce(
      listResponse([liveRow(1), liveRow(2)]),
    )
    await (wrapper.vm as unknown as { refresh: () => Promise<void> }).refresh()
    let completeOld!: (value: NotificationListResponse) => void
    let completeFresh!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeFresh = resolve
          }),
      )
    await receiveNotification(3)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)
    await wrapper.get('[data-testid="mark-all-read"]').trigger('click')
    await flushPromises()
    expect(mocks.notificationApi.markAllAsRead).toHaveBeenCalledTimes(1)
    expect(stale.every((row) => !row.isRead)).toBe(true)
    completeOld(listResponse(stale))
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(4)
    expect(wrapper.findAll('button[aria-label="notification.list.markRead"]')).toHaveLength(0)
    expect(wrapper.findAll('button[aria-label="notification.list.markUnread"]')).toHaveLength(2)
    completeFresh(listResponse(stale.map((row) => ({ ...row, isRead: true }))))
    await flushPromises()
    expect(wrapper.findAll('button[aria-label="notification.list.markUnread"]')).toHaveLength(2)
    wrapper.unmount()
  })

  it('N5追加: snooze成功前に取得した旧GETで除去済みrowを再挿入しない', async () => {
    const stale = liveRow(1)
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let completeOld!: (value: NotificationListResponse) => void
    let completeFresh!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<NotificationListResponse>((resolve) => {
            completeFresh = resolve
          }),
      )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    // PrimeVue Menuへ渡された公開modelから、実際のプリセットcommandを実行する。
    wrapper.getComponent(stubs.Menu).props('model')[0].command()
    await flushPromises()
    expect(mocks.notificationApi.snooze).toHaveBeenCalledWith(1, expect.any(String))
    expect(mocks.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ summary: 'inbox.action.snoozed' }),
    )
    completeOld(listResponse([stale]))
    await flushPromises()
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-notification-id="1"]').exists()).toBe(false)
    completeFresh(listResponse([]))
    await flushPromises()
    expect(wrapper.find('[data-notification-id="1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('N5追加: snooze失敗とfilter変更が競合しても旧rowを挿入せず現filterを再取得する', async () => {
    const stale = { ...liveRow(1), isRead: true }
    const wrapper = await mountList({ ...liveRow(1), isRead: true })
    await flushPromises()
    let completeOld!: (value: NotificationListResponse) => void
    let rejectSnooze!: (error: Error) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          completeOld = resolve
        }),
    )
    mocks.notificationApi.snooze.mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectSnooze = reject
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    wrapper.getComponent(stubs.Menu).props('model')[0].command()
    await flushPromises()
    expect(mocks.notificationApi.snooze).toHaveBeenCalledWith(1, expect.any(String))
    wrapper.getComponent(stubs.SelectButton).vm.$emit('update:modelValue', 'unread')
    await flushPromises()
    rejectSnooze(new Error('fixture snooze failure'))
    await flushPromises()
    expect(mocks.showError).toHaveBeenCalledWith('inbox.action.snoozeFailed')
    expect(wrapper.find('[data-notification-id="1"]').exists()).toBe(false)
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(3)]))
    completeOld(listResponse([stale]))
    await flushPromises()
    expect(wrapper.find('[data-notification-id="1"]').exists()).toBe(false)
    expect(wrapper.find('[data-notification-id="3"]').exists()).toBe(true)
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({
      page: 0,
      size: 20,
      isRead: false,
    })
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('N5: refresh取得失敗を既存エラーで表示し、現在の通知を消さない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    mocks.notificationApi.getNotifications.mockRejectedValueOnce(
      new Error('fixture network failure'),
    )
    await receiveNotification(2)
    expect(mocks.showError).toHaveBeenCalledWith('notification.list.loadError')
    expect(wrapper.text()).toContain('通知 1')
    wrapper.unmount()
  })

  it('N5: 古いfilterの遅着応答を現在の未読filterへ適用しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          complete = resolve
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    wrapper.findComponent(stubs.SelectButton).vm.$emit('update:modelValue', 'unread')
    await flushPromises()
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(3)]))
    complete(listResponse([{ ...liveRow(2), isRead: true }]))
    await flushPromises()
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).not.toContain('通知 2')
    expect(mocks.notificationApi.getNotifications).toHaveBeenLastCalledWith({
      page: 0,
      size: 20,
      isRead: false,
    })
    wrapper.unmount()
  })

  it('N5: Aの遅着応答をB一覧へ適用しない', async () => {
    const wrapper = await mountList(liveRow(1))
    await flushPromises()
    let complete!: (value: NotificationListResponse) => void
    mocks.notificationApi.getNotifications.mockImplementationOnce(
      () =>
        new Promise<NotificationListResponse>((resolve) => {
          complete = resolve
        }),
    )
    await receiveNotification(2)
    expect(mocks.notificationApi.getNotifications).toHaveBeenCalledTimes(2)
    mocks.authStore.user = { id: 43, timezone: 'Asia/Tokyo' }
    mocks.notificationApi.getNotifications.mockResolvedValue(listResponse([liveRow(3)]))
    complete(listResponse([liveRow(2)]))
    await flushPromises()
    expect(wrapper.text()).toContain('通知 3')
    expect(wrapper.text()).not.toContain('通知 2')
    wrapper.unmount()
  })
})
