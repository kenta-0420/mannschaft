import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { SocialProfile } from '~/types/social-profile'
import type { InvitationResponse, ParentLinkResponse } from '~/types/parental-consent'

/**
 * CMP-260909-1141 Phase 4 のユニットテスト。
 *
 * <p>最後の4枚（/profile/followers・/profile/following・/settings/profile-visibility・
 * /parental-consent/manage）への導線を検証する。</p>
 *
 * テストケース一覧:
 *  P4-CARD-001: SocialProfileCard のフォロワー数がリンクとして描画され、href が /profile/followers
 *  P4-CARD-002: SocialProfileCard のフォロー中数がリンクとして描画され、href が /profile/following
 *  P4-SETTINGS-001: settings/index.vue の individualItems に profile-visibility が出る
 *  P4-SETTINGS-002: settings/index.vue の individualItems に parental-consent/manage が出る
 *  P4-PENDING-001: pending.vue は承認済み保護者がいれば /parental-consent/manage へ遷移する（/ ではない）
 *  P4-PENDING-002: pending.vue は承認済み保護者がいなければ遷移しない
 */

const mockNavigateTo = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigateTo(...args))

const invitations: InvitationResponse[] = []
let mockParents: ParentLinkResponse[] = []

vi.mock('~/composables/useParentalConsentApi', () => ({
  useParentalConsentApi: () => ({
    sendInvitation: vi.fn(),
    getInvitations: () => Promise.resolve(invitations),
    cancelInvitation: vi.fn(),
    getParents: () => Promise.resolve(mockParents),
  }),
}))

const sampleProfile: SocialProfile = {
  id: 1,
  handle: 'taro',
  displayName: '太郎',
  avatarUrl: null,
  bio: null,
  isActive: true,
  followerCount: 12,
  followingCount: 34,
  createdAt: '2026-01-01T00:00:00',
}

const SocialProfileCard = (await import('~/components/social/SocialProfileCard.vue')).default
const SettingsIndexPage = (await import('~/pages/settings/index.vue')).default
const PendingPage = (await import('~/pages/parental-consent/pending.vue')).default

describe('Phase 4 SocialProfileCard 導線', () => {
  // mountSuspended の初回マウントが timeout を食う既知の罠への対処（既存作法に倣うウォームアップ）。
  beforeAll(async () => {
    await mountSuspended(SocialProfileCard, { props: { profile: sampleProfile } })
  }, 30000)

  it('P4-CARD-001: フォロワー数が /profile/followers へのリンクとして出る', async () => {
    const wrapper = await mountSuspended(SocialProfileCard, { props: { profile: sampleProfile } })
    const html = wrapper.html()
    expect(html).toContain('href="/profile/followers"')
    expect(html).toContain('12')
  })

  it('P4-CARD-002: フォロー中数が /profile/following へのリンクとして出る', async () => {
    const wrapper = await mountSuspended(SocialProfileCard, { props: { profile: sampleProfile } })
    const html = wrapper.html()
    expect(html).toContain('href="/profile/following"')
    expect(html).toContain('34')
  })
})

describe('Phase 4 設定ハブ導線', () => {
  it('P4-SETTINGS-001/002: profile-visibility と parental-consent/manage が individualItems に出る', async () => {
    const wrapper = await mountSuspended(SettingsIndexPage)
    // 個別設定一覧はアコーディオンで初期折りたたみのため、開いてから検証する
    const toggleButton = wrapper.findAll('button').find(b => b.text().includes('個別設定一覧'))
    expect(toggleButton).toBeTruthy()
    await toggleButton!.trigger('click')
    await wrapper.vm.$nextTick()
    const html = wrapper.html()
    expect(html).toContain('/settings/profile-visibility')
    expect(html).toContain('/parental-consent/manage')
  })
})

describe('Phase 4 保護者同意 pending → manage 遷移', () => {
  it('P4-PENDING-001: 承認済み保護者がいれば /parental-consent/manage へ遷移する（/ ではない）', async () => {
    mockNavigateTo.mockClear()
    mockParents = [{ linkId: 'p1', parentEmail: 'parent@example.com', parentUserId: 1, approvedAt: '2026-01-01T00:00:00' }]

    await mountSuspended(PendingPage)
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(mockNavigateTo).toHaveBeenCalledWith('/parental-consent/manage')
    expect(mockNavigateTo).not.toHaveBeenCalledWith('/')
  })

  it('P4-PENDING-002: 承認済み保護者がいなければ遷移しない', async () => {
    mockNavigateTo.mockClear()
    mockParents = []

    await mountSuspended(PendingPage)
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(mockNavigateTo).not.toHaveBeenCalled()
  })
})
