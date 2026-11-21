import { computed, defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import AnnouncementsPage from '~/pages/teams/[slug]/announcements.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

const state = vi.hoisted(() => ({ admin: false }))
// 実 BE 応答（authorId のみで author を持たない）を adapter に通した結果を一覧へ流す
const raw = (id: number, title: string, authorId?: number) => ({
  id, scopeType: 'TEAM', scopeId: 10, sourceType: 'BLOG_POST', sourceId: id, isPinned: false, isRead: true, titleCache: title, authorId,
})
const feed = ref([
  toAnnouncementItem(raw(1, '自分の項目', 7)),
  toAnnouncementItem(raw(2, '他人の項目', 8)),
  toAnnouncementItem(raw(3, '著者なし')),
])

mockNuxtImport('useRoute', () => () => ({ params: { slug: 't' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdmin: computed(() => state.admin), loadPermissions: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))
mockNuxtImport('useContentGateApi', () => () => ({ checkAccess: vi.fn() }))
mockNuxtImport('useSpotlightApi', () => () => ({ fetchContent: vi.fn().mockResolvedValue([]) }))
mockNuxtImport('useAnnouncementPreview', () => () => ({
  state: ref('closed'), preview: ref(null), item: ref(null), error: ref(null), trigger: ref(null),
  open: vi.fn(), close: vi.fn(), retry: vi.fn(), markDisplayed: vi.fn(),
}))
mockNuxtImport('useAnnouncementFeed', () => () => ({
  feed, meta: ref({ unreadCount: 0, hasNext: false, nextCursor: null }), loading: ref(false), error: ref(null),
  fetchFeed: vi.fn(), togglePin: vi.fn(), deleteAnnouncement: vi.fn(), markAsReadBeforeOpen: vi.fn(),
  markAllAsRead: vi.fn(), setReadLocally: vi.fn(), removeFromFeedLocally: vi.fn(),
}))

const Item = defineComponent({
  props: { item: { type: Object, required: true }, showPinControl: Boolean, canRemove: Boolean },
  setup(props) {
    return () => h('div', {
      'data-testid': `item-${(props.item as { id: number }).id}`,
      'data-pin': String(props.showPinControl),
      'data-remove': String(props.canRemove),
    })
  },
})
const passthrough = defineComponent({ setup(_p, { slots }) { return () => h('div', slots.default?.()) } })

let wrapper: Awaited<ReturnType<typeof mountPage>> | undefined
async function mountPage() {
  return mountSuspended(AnnouncementsPage, {
    global: {
      stubs: {
        AnnouncementItem: Item, SectionCard: passthrough, PageHeader: true, PageLoading: true,
        DashboardErrorState: true, DashboardEmptyState: true, SpotlightSlot: true,
        Dialog: true, PaywallLock: true, AnnouncementDetailModal: true,
      },
    },
  })
}
function flags(id: number) {
  const el = wrapper!.get(`[data-testid="item-${id}"]`)
  return { pin: el.attributes('data-pin'), remove: el.attributes('data-remove') }
}

describe('D-3: チームお知らせ一覧のピン留め/解除の出し分け', () => {
  beforeEach(() => {
    state.admin = false
    useAuthStore().user = { id: 7, email: 'a@example.com', fullName: '私', profileImageUrl: null }
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })

  it('非 ADMIN は自分が著者の項目だけ解除(×)でき、ピン留めは出ない', async () => {
    wrapper = await mountPage()
    expect(flags(1)).toEqual({ pin: 'false', remove: 'true' })
    expect(flags(2)).toEqual({ pin: 'false', remove: 'false' })
    expect(flags(3)).toEqual({ pin: 'false', remove: 'false' })
  })

  it('ADMIN は全項目でピン留めと解除ができる', async () => {
    state.admin = true
    wrapper = await mountPage()
    for (const id of [1, 2, 3]) expect(flags(id)).toEqual({ pin: 'true', remove: 'true' })
  })

  it('未ログイン（user なし）の非 ADMIN には何も出ない', async () => {
    useAuthStore().user = null
    wrapper = await mountPage()
    for (const id of [1, 2, 3]) expect(flags(id)).toEqual({ pin: 'false', remove: 'false' })
  })
})
