import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamNavPage from '~/pages/teams/[slug]/nav.vue'

/**
 * ALIC-1/2 回帰テスト（CMP-261011-1237）: 「フレンドチーム」タブの実体は nav.vue。
 * 「使い方」ボタンと「フォロー」ボタンは孤立ページ friends.vue にしか無く、利用者が到達できなかった。
 * タブ本体（nav.vue）にツールバーとして統合したことを固定する。
 *
 *   NAVF-001 所属者全員に「使い方」ボタンが出る
 *   NAVF-002 ADMIN に「フォロー」ボタンが出る
 *   NAVF-003 MANAGE_FRIEND_TEAMS を持つ DEPUTY_ADMIN に「フォロー」が出る
 *   NAVF-004 MANAGE_FRIEND_TEAMS を持たない DEPUTY_ADMIN / MEMBER には出ない
 *   NAVF-005 SUPPORTER には注意バナーが出る
 *   NAVF-006 ボタンは折り返し・縮小禁止（モバイルで潰れない）
 */

const shell = vi.hoisted(() => ({
  roleName: 'ADMIN' as string | null,
  isAdmin: true,
  isAdminOrDeputy: true,
  permissions: [] as string[],
}))

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({
    roleName: { value: shell.roleName },
    isAdmin: { value: shell.isAdmin },
    isAdminOrDeputy: { value: shell.isAdminOrDeputy },
  }),
}))

vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    loadPermissions: vi.fn(async () => ({ ok: true })),
    can: (p: string) => shell.permissions.includes(p),
    roleName: { value: shell.roleName },
    isAdmin: { value: shell.isAdmin },
    isAdminOrDeputy: { value: shell.isAdminOrDeputy },
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))

const stubs = { TeamFriendList: true, FriendFollowDialog: true, FriendTeamGuideModal: true }

function setRole(role: string, permissions: string[] = []) {
  shell.roleName = role
  shell.isAdmin = role === 'ADMIN'
  shell.isAdminOrDeputy = role === 'ADMIN' || role === 'DEPUTY_ADMIN'
  shell.permissions = permissions
}

async function mountNav() {
  return mountSuspended(TeamNavPage, { global: { stubs } })
}

beforeAll(async () => {
  const warmup = await mountNav()
  warmup.unmount()
})

describe('pages/teams/[slug]/nav.vue — フレンドチームタブのツールバー', () => {
  beforeEach(() => setRole('ADMIN'))

  it('NAVF-001: 所属者（MEMBER）にも「使い方」ボタンが出る', async () => {
    setRole('MEMBER')
    const w = await mountNav()
    expect(w.find('[data-testid="friend-guide-button"]').exists()).toBe(true)
  })

  it('NAVF-002: ADMIN に「フォロー」ボタンが出る', async () => {
    const w = await mountNav()
    expect(w.find('[data-testid="friend-follow-button"]').exists()).toBe(true)
  })

  it('NAVF-003: MANAGE_FRIEND_TEAMS を持つ DEPUTY_ADMIN に「フォロー」が出る', async () => {
    setRole('DEPUTY_ADMIN', ['MANAGE_FRIEND_TEAMS'])
    const w = await mountNav()
    expect(w.find('[data-testid="friend-follow-button"]').exists()).toBe(true)
  })

  it('NAVF-004: 権限なし DEPUTY_ADMIN / MEMBER には「フォロー」が出ない', async () => {
    setRole('DEPUTY_ADMIN')
    expect((await mountNav()).find('[data-testid="friend-follow-button"]').exists()).toBe(false)
    setRole('MEMBER')
    expect((await mountNav()).find('[data-testid="friend-follow-button"]').exists()).toBe(false)
  })

  it('NAVF-005: SUPPORTER には注意バナーが出る', async () => {
    setRole('SUPPORTER')
    const w = await mountNav()
    expect(w.find('[data-testid="friend-supporter-notice"]').exists()).toBe(true)
  })

  it('NAVF-006: ボタンは whitespace-nowrap / shrink-0 で潰れず、行は flex-wrap', async () => {
    const w = await mountNav()
    const bar = w.find('[data-testid="friend-toolbar"]')
    expect(bar.classes()).toContain('flex-wrap')
    for (const id of ['friend-guide-button', 'friend-follow-button']) {
      const cls = w.find(`[data-testid="${id}"]`).classes()
      expect(cls).toContain('whitespace-nowrap')
      expect(cls).toContain('shrink-0')
    }
  })
})
