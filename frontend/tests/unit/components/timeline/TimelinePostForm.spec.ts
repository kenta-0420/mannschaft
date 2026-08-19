import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TimelinePostForm from '~/components/timeline/TimelinePostForm.vue'

/**
 * CMP-058 配下配信（deliveryScope）— TimelinePostForm.vue ユニットテスト。
 *
 * 検証観点:
 *   UNIT-TL-DELIVERY-001: 組織スコープでも ADMIN/DEPUTY_ADMIN でなければ配信範囲の選択肢は出ない
 *   UNIT-TL-DELIVERY-002: 組織スコープ かつ ADMIN/DEPUTY_ADMIN なら選択肢が出る（既定は DIRECT）
 *   UNIT-TL-DELIVERY-003: チームスコープでは権限があっても出ない（deliveryScope が効かないため）
 *   UNIT-TL-DELIVERY-004: 既定のまま投稿すると deliveryScope はペイロードに乗らない（BE 既定 = DIRECT）
 *   UNIT-TL-DELIVERY-005: DESCENDANTS を選ぶとペイロードに deliveryScope: 'DESCENDANTS' が乗る
 */

const createPost = vi.fn()
/** useRoleAccess が返すロール判定。テストごとに切り替える。 */
const isAdminOrDeputy = ref(false)
const loadPermissions = vi.fn().mockResolvedValue({ ok: true })

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  te: () => true,
}))

mockNuxtImport('useNotification', () => () => ({
  showSuccess: vi.fn(),
  showError: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  showInfo: vi.fn(),
  showWarn: vi.fn(),
}))

mockNuxtImport('useRoleAccess', () => () => ({
  permissions: ref<string[]>([]),
  roleName: ref(null),
  loading: ref(false),
  loadPermissions,
  can: () => false,
  isAdmin: ref(false),
  isAdminOrDeputy,
  isMember: ref(true),
}))

mockNuxtImport('useTimelineApi', () => () => ({
  createPost,
  getImageUploadUrl: vi.fn(),
  getVideoUploadUrl: vi.fn(),
}))

describe('TimelinePostForm.vue — 配信範囲（deliveryScope）', () => {
  beforeEach(() => {
    createPost.mockReset()
    createPost.mockResolvedValue({ data: { id: 1 } })
    isAdminOrDeputy.value = false
  })

  it('UNIT-TL-DELIVERY-001: 権限が無いユーザーには配信範囲の選択肢が表示されない', async () => {
    isAdminOrDeputy.value = false
    const wrapper = await mountSuspended(TimelinePostForm, {
      props: { scopeType: 'ORGANIZATION', scopeId: 'my-org' },
    })
    expect(wrapper.find('[data-testid="timeline-delivery-scope"]').exists()).toBe(false)
  })

  it('UNIT-TL-DELIVERY-002: ADMIN/DEPUTY_ADMIN には選択肢が表示され、既定は DIRECT', async () => {
    isAdminOrDeputy.value = true
    const wrapper = await mountSuspended(TimelinePostForm, {
      props: { scopeType: 'ORGANIZATION', scopeId: 'my-org' },
    })
    expect(wrapper.find('[data-testid="timeline-delivery-scope"]').exists()).toBe(true)
    // 3択すべてが描画される
    for (const option of ['DIRECT', 'CHILDREN', 'DESCENDANTS']) {
      expect(wrapper.find(`[data-testid="timeline-delivery-scope-${option}"]`).exists()).toBe(true)
    }
    // 既定（DIRECT）のときは補足文を出さない（段階開示）
    expect(wrapper.find('[data-testid="timeline-delivery-scope-hint"]').exists()).toBe(false)
  })

  it('UNIT-TL-DELIVERY-003: チームスコープでは権限があっても配信範囲は表示されない', async () => {
    isAdminOrDeputy.value = true
    const wrapper = await mountSuspended(TimelinePostForm, {
      props: { scopeType: 'TEAM', scopeId: 'my-team' },
    })
    expect(wrapper.find('[data-testid="timeline-delivery-scope"]').exists()).toBe(false)
  })

  it('UNIT-TL-DELIVERY-004: 既定のまま投稿すると deliveryScope はペイロードに含まれない', async () => {
    isAdminOrDeputy.value = true
    const wrapper = await mountSuspended(TimelinePostForm, {
      props: { scopeType: 'ORGANIZATION', scopeId: 'my-org' },
    })
    await wrapper.get('textarea').setValue('おしらせです')
    await wrapper.get('[data-testid="team-timeline-submit"]').trigger('click')

    expect(createPost).toHaveBeenCalledTimes(1)
    const body = createPost.mock.calls[0]![0] as Record<string, unknown>
    expect(body.deliveryScope).toBeUndefined()
  })

  it('UNIT-TL-DELIVERY-005: DESCENDANTS を選ぶとペイロードに乗る', async () => {
    isAdminOrDeputy.value = true
    const wrapper = await mountSuspended(TimelinePostForm, {
      props: { scopeType: 'ORGANIZATION', scopeId: 'my-org' },
    })
    const radio = wrapper.get('[data-testid="timeline-delivery-scope-DESCENDANTS"] input')
    await radio.trigger('change')

    // 既定以外を選んだので補足文が出る（段階開示）
    expect(wrapper.find('[data-testid="timeline-delivery-scope-hint"]').exists()).toBe(true)

    await wrapper.get('textarea').setValue('配下すべてへのお知らせ')
    await wrapper.get('[data-testid="team-timeline-submit"]').trigger('click')

    expect(createPost).toHaveBeenCalledTimes(1)
    const body = createPost.mock.calls[0]![0] as Record<string, unknown>
    expect(body.deliveryScope).toBe('DESCENDANTS')
    expect(body.scopeType).toBe('ORGANIZATION')
  })
})
