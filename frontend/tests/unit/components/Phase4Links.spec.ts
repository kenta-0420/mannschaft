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
 *  P4-SETTINGS-003: settings/index.vue の全項目 i18n 化がロケール切替に追従する（computed 化の検証）
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
    // 個別設定一覧はアコーディオンで初期折りたたみのため、開いてから検証する。
    // ラベルは i18n 化されておりロケール依存になるため、テキストではなく
    // トグルボタン固有のアイコン（pi-list）で選択する（ロケール非依存）。
    const toggleButton = wrapper.findAll('button').find(b => b.find('.pi-list').exists())
    expect(toggleButton).toBeTruthy()
    await toggleButton!.trigger('click')
    await wrapper.vm.$nextTick()
    const html = wrapper.html()
    expect(html).toContain('/settings/profile-visibility')
    expect(html).toContain('/parental-consent/manage')
  })

  it('P4-SETTINGS-003: ロケール切替に追従してラベルが更新される（computed 化の検証）', async () => {
    const wrapper = await mountSuspended(SettingsIndexPage)
    const i18n = (wrapper.vm.$i18n as { locale: string, setLocale?: (l: string) => Promise<void> })

    /**
     * ロケール切替（動的インポートで新しいメッセージカタログを読み込む非同期処理）が
     * DOM に反映されるまで待つ。`$nextTick()` 1回では非同期ロードの完了を待てず
     * 「キー名がそのまま描画される」状態を掴んでしまう（実測済み）ため、
     * カタログ読み込み＋再描画が終わるまでポーリングする。
     */
    async function switchLocaleAndSettle(locale: string) {
      if (i18n.setLocale) {
        await i18n.setLocale(locale)
      } else {
        i18n.locale = locale
      }
      for (let i = 0; i < 20; i++) {
        await wrapper.vm.$nextTick()
        await new Promise((resolve) => setTimeout(resolve, 25))
        if (!wrapper.html().includes('settings.account.page_title')) break
      }
    }

    // テスト環境の既定ロケールに依存しないよう、明示的に ja → en → ja と切り替えて
    // 双方向の追従を見る。テンプレート内の日本語コメント（<!-- アカウント設定（メイン） -->）は
    // 実装コメントであり描画テキストではないため、実際にレンダリングされる要素（<p>）のみを対象に検証する。
    //
    // setup 時に t() を1回だけ呼ぶ実装（非 computed）だと、マウント時のロケールで
    // ラベルが固定され、以後ロケールを切り替えても更新されない
    // （CMP-260909-1141 Phase 1 で踏んだ罠と同型）。
    await switchLocaleAndSettle('ja')
    expect(wrapper.html()).toContain('<p class="text-lg font-semibold">アカウント設定</p>')
    expect(wrapper.html()).not.toContain('<p class="text-lg font-semibold">Account Settings</p>')

    await switchLocaleAndSettle('en')
    expect(wrapper.html()).toContain('<p class="text-lg font-semibold">Account Settings</p>')
    expect(wrapper.html()).not.toContain('<p class="text-lg font-semibold">アカウント設定</p>')

    // 後続テストへの影響を避けるため元に戻す。
    await switchLocaleAndSettle('ja')
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
