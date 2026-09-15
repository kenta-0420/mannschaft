import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'

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

  it('ROLE-005: 別スコープの古いADMIN応答は現在のMEMBER権限を上書きしない', async () => {
    const selectedScope = ref('old-team')
    let resolveOld!: (value: { data: { roleName: string, permissions: string[] } }) => void
    mockFetch.mockImplementation((path: string) =>
      path.includes('/old-team/')
        ? new Promise(resolve => { resolveOld = resolve })
        : Promise.resolve({ data: { roleName: 'MEMBER', permissions: [] } }),
    )

    const access = useRoleAccess('team', selectedScope)
    const oldRequest = access.loadPermissions()
    selectedScope.value = 'new-team'
    expect(await access.loadPermissions()).toEqual({ ok: true })
    expect(access.isAdminOrDeputy.value).toBe(false)

    resolveOld({ data: { roleName: 'ADMIN', permissions: [] } })
    expect(await oldRequest).toEqual({ ok: false })
    expect(access.isAdminOrDeputy.value).toBe(false)
  })

  it('ROLE-006: 別スコープの古い取得失敗は現在のADMIN権限を消さない', async () => {
    const selectedScope = ref('old-team')
    let rejectOld!: (reason: Error) => void
    mockFetch.mockImplementation((path: string) =>
      path.includes('/old-team/')
        ? new Promise((_resolve, reject) => { rejectOld = reject })
        : Promise.resolve({ data: { roleName: 'ADMIN', permissions: [] } }),
    )

    const access = useRoleAccess('team', selectedScope)
    const oldRequest = access.loadPermissions()
    selectedScope.value = 'new-team'
    expect(await access.loadPermissions()).toEqual({ ok: true })
    expect(access.isAdminOrDeputy.value).toBe(true)

    rejectOld(new Error('old scope unavailable'))
    expect(await oldRequest).toEqual({ ok: false })
    expect(access.isAdminOrDeputy.value).toBe(true)
  })
})
