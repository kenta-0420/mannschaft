import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useRoleAccess ユニットテスト（team-breakdown follow-up①）
 *
 * 背景: アンケ結果のチーム別内訳パネル表示ガードを isAdminPlus(DEPUTY 除外) から
 * isAdminOrDeputy(DEPUTY 含む) へ統一した。出欠側（EventDetailPanel = isAdminOrDeputy）
 * および BE 認可（checkAdminOrAbove = ADMIN/DEPUTY_ADMIN 許可）と一致させるための判定基盤。
 *
 * 検証観点（ガードが依拠する isAdminOrDeputy の真偽）:
 *   ROLE-001: DEPUTY_ADMIN は isAdminOrDeputy=true（過小露出の是正）
 *   ROLE-002: ADMIN/SYSTEM_ADMIN は従来どおり true
 *   ROLE-003: MEMBER/SUPPORTER/GUEST は false（漏洩を新たに作らない）
 *   ROLE-004: isAdmin は DEPUTY_ADMIN で false のまま（=旧ガードは DEPUTY を弾いていた）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useRoleAccess } from '~/composables/useRoleAccess'

async function loadWithRole(roleName: string) {
  mockFetch.mockResolvedValueOnce({ data: { roleName, permissions: [] } })
  const access = useRoleAccess('organization', 'org-1')
  await access.loadPermissions()
  return access
}

describe('useRoleAccess', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ROLE-001: DEPUTY_ADMIN は isAdminOrDeputy=true', async () => {
    const access = await loadWithRole('DEPUTY_ADMIN')
    expect(access.isAdminOrDeputy.value).toBe(true)
  })

  it('ROLE-002: ADMIN/SYSTEM_ADMIN は isAdminOrDeputy=true', async () => {
    expect((await loadWithRole('ADMIN')).isAdminOrDeputy.value).toBe(true)
    expect((await loadWithRole('SYSTEM_ADMIN')).isAdminOrDeputy.value).toBe(true)
  })

  it('ROLE-003: MEMBER/SUPPORTER/GUEST は isAdminOrDeputy=false（漏洩なし）', async () => {
    expect((await loadWithRole('MEMBER')).isAdminOrDeputy.value).toBe(false)
    expect((await loadWithRole('SUPPORTER')).isAdminOrDeputy.value).toBe(false)
    expect((await loadWithRole('GUEST')).isAdminOrDeputy.value).toBe(false)
  })

  it('ROLE-004: DEPUTY_ADMIN は isAdmin=false（旧ガード isAdminPlus は DEPUTY を弾いていた）', async () => {
    const access = await loadWithRole('DEPUTY_ADMIN')
    expect(access.isAdmin.value).toBe(false)
  })

  // 対象スコープの ADMIN 割当の有無（BE の AccessControlService.isAdmin と同じ判定の結果）。
  // roleName は「表示用の最強ロール」で SYSTEM_ADMIN が優先されるため、ADMIN 判定には使えない。
  async function loadScopeAdmin(roleName: string, scopeAdmin?: boolean) {
    mockFetch.mockResolvedValueOnce({ data: { roleName, permissions: [], scopeAdmin } })
    const access = useRoleAccess('organization', 'org-1')
    await access.loadPermissions()
    return access.isScopeAdmin.value
  }

  it('ROLE-005: SYSTEM_ADMIN のみ（対象組織の ADMIN 割当なし）は isScopeAdmin=false', async () => {
    expect(await loadScopeAdmin('SYSTEM_ADMIN', false)).toBe(false)
  })

  it('ROLE-006: SYSTEM_ADMIN と対象組織 ADMIN の兼任は roleName が SYSTEM_ADMIN でも isScopeAdmin=true', async () => {
    expect(await loadScopeAdmin('SYSTEM_ADMIN', true)).toBe(true)
  })

  it('ROLE-007: 対象組織の ADMIN 単独は isScopeAdmin=true', async () => {
    expect(await loadScopeAdmin('ADMIN', true)).toBe(true)
  })

  it('ROLE-008: 他組織の ADMIN だけ（対象組織では MEMBER 等・未所属）は isScopeAdmin=false', async () => {
    expect(await loadScopeAdmin('MEMBER', false)).toBe(false)
    expect(await loadScopeAdmin('GUEST', false)).toBe(false)
  })

  it('ROLE-009: scopeAdmin を返さない旧応答は roleName === ADMIN で判定し、SYSTEM_ADMIN は不可', async () => {
    expect(await loadScopeAdmin('ADMIN')).toBe(true)
    expect(await loadScopeAdmin('SYSTEM_ADMIN')).toBe(false)
  })

  it('ROLE-010: 取得失敗時は isScopeAdmin=false', async () => {
    mockFetch.mockRejectedValueOnce(new Error('500'))
    const access = useRoleAccess('organization', 'org-1')
    await access.loadPermissions()
    expect(access.isScopeAdmin.value).toBe(false)
  })
})
