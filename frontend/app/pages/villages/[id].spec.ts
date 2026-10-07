import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import type { MembershipResponse, VillageResponse } from '~/types/village'
import VillagePage from '~/pages/villages/[id].vue'

const mocks = vi.hoisted(() => ({
  join: vi.fn(),
  getVillage: vi.fn(),
  listMembers: vi.fn(),
  refreshVillage: vi.fn(async () => {}),
  warn: vi.fn(),
  handleApiError: vi.fn(),
}))

const village: VillageResponse = {
  id: '01900000-0000-7000-8000-000000000001',
  slug: 'membership-warning-fixture',
  name: '警告テスト村',
  description: null,
  type: 'COMMUNITY',
  joinPolicy: 'FREE',
  visibility: 'PUBLIC',
  bulletinVisibility: 'MEMBERS_ONLY',
  category: null,
  iconUrl: null,
  coverUrl: null,
  monshoUrl: null,
  guidelineMd: null,
  memberCount: 0,
  isOfficial: false,
  isMember: false,
  isPinned: false,
  myRole: null,
  archivedAt: null,
  createdAt: '2026-10-05T12:00:00',
  updatedAt: '2026-10-05T12:00:00',
  version: 0,
}

const membership: Omit<MembershipResponse, 'participationWarn'> = {
  id: '01900000-0000-7000-8000-000000000002',
  subjectType: 'USER',
  subjectId: 7,
  displayName: null,
  role: 'VILLAGER',
  joinedAt: '2026-10-05T12:00:00',
  isBanned: false,
}

mockNuxtImport('useRoute', () => () => ({
  params: { id: '01900000-0000-7000-8000-000000000001' },
  path: '/villages/01900000-0000-7000-8000-000000000001/bulletin',
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useVillageApi', () => () => ({
  joinVillage: mocks.join,
  getVillage: mocks.getVillage,
  listMembers: mocks.listMembers,
}))
mockNuxtImport('useAuthStore', () => () => ({ currentUser: { id: 7 } }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useNotification', () => () => ({ warn: mocks.warn }))
mockNuxtImport('useAsyncData', () => async () => ({
  data: ref(village),
  error: ref(null),
  status: ref('success'),
  refresh: mocks.refreshVillage,
}))

const VillageHeaderStub = defineComponent({
  name: 'VillageHeader',
  emits: ['join'],
  setup(_props, { emit }) {
    return () => h('button', {
      'data-testid': 'join-through-header',
      onClick: () => emit('join'),
    }, '参加')
  },
})

async function mountPage() {
  return mountSuspended(VillagePage, {
    global: {
      stubs: {
        VillageHeader: VillageHeaderStub,
        NuxtPage: true,
        VillageReportDialog: true,
        VillageEditDialog: true,
        VillageGuideModal: true,
      },
    },
  })
}

beforeAll(async () => {
  const warmup = await mountPage()
  warmup.unmount()
})

beforeEach(() => {
  mocks.join.mockReset()
  mocks.getVillage.mockReset()
  mocks.listMembers.mockReset()
  mocks.warn.mockReset()
  mocks.handleApiError.mockReset()
  mocks.refreshVillage.mockReset().mockResolvedValue(undefined)
})

describe('pages/villages/[id].vue 本人FREE参加の成功警告', () => {
  it('trueなら参加警告を1回通知し再取得する', async () => {
    mocks.join.mockResolvedValue({ ...membership, participationWarn: true })
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="join-through-header"]').trigger('click')
    await flushPromises()

    expect(mocks.join).toHaveBeenCalledOnce()
    expect(mocks.join).toHaveBeenCalledWith(village.id, { subjectType: 'USER', subjectId: 7 })
    expect(mocks.warn).toHaveBeenCalledOnce()
    expect(mocks.warn).toHaveBeenCalledWith('village.warn.participationLimit')
    expect(mocks.refreshVillage).toHaveBeenCalledOnce()
  })

  it.each([false, undefined])('%sなら参加警告なしで再取得する', async (participationWarn) => {
    mocks.join.mockResolvedValue({ ...membership, participationWarn })
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="join-through-header"]').trigger('click')
    await flushPromises()

    expect(mocks.join).toHaveBeenCalledOnce()
    expect(mocks.warn).not.toHaveBeenCalled()
    expect(mocks.refreshVillage).toHaveBeenCalledOnce()
  })

  it('参加失敗時は成功警告なしで既エラー処理へ渡す', async () => {
    const error = { statusCode: 429, data: { error: { code: 'VILLAGE_012' } } }
    mocks.join.mockRejectedValue(error)
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="join-through-header"]').trigger('click')
    await flushPromises()

    expect(mocks.join).toHaveBeenCalledOnce()
    expect(mocks.warn.mock.calls.filter(([summary]) => summary === 'village.warn.participationLimit')).toHaveLength(0)
    expect(mocks.handleApiError).toHaveBeenCalledWith(error, 'village.action.join')
    expect(mocks.refreshVillage).not.toHaveBeenCalled()
  })
})
