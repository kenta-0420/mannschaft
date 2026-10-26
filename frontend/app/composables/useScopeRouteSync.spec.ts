import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { parseScopeRoute, useScopeRouteSync } from './useScopeRouteSync'

function team(id: number, slug: string, name: string, nickname1: string | null = null) {
  return {
    id,
    slug,
    name,
    nickname1,
    iconUrl: null,
    role: 'ADMIN',
    template: 'DEFAULT',
    memberCount: 1,
  }
}

function org(id: number, slug: string, name: string, nickname1: string | null = null) {
  return {
    id,
    slug,
    name,
    nickname1,
    iconUrl: null,
    role: 'ADMIN',
    orgType: 'DEFAULT',
    memberCount: 1,
  }
}

describe('parseScopeRoute', () => {
  it('チーム個別ページを team スコープとして解決する', () => {
    expect(parseScopeRoute('/teams/alpha')).toEqual({ scopeType: 'team', slug: 'alpha' })
    expect(parseScopeRoute('/teams/alpha/schedule')).toEqual({ scopeType: 'team', slug: 'alpha' })
  })

  it('組織個別ページを organization スコープとして解決する', () => {
    expect(parseScopeRoute('/organizations/beta')).toEqual({
      scopeType: 'organization',
      slug: 'beta',
    })
  })

  it('クエリ・ハッシュ付きでも slug を取り違えない', () => {
    expect(parseScopeRoute('/teams/alpha?tab=info#top')).toEqual({
      scopeType: 'team',
      slug: 'alpha',
    })
  })

  it('ハブ・検索・無関係なパスは null', () => {
    expect(parseScopeRoute('/teams')).toBeNull()
    expect(parseScopeRoute('/teams/search')).toBeNull()
    expect(parseScopeRoute('/organizations/search')).toBeNull()
    expect(parseScopeRoute('/admin/receipts')).toBeNull()
    expect(parseScopeRoute('/')).toBeNull()
  })
})

describe('useScopeRouteSync', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('所属チームのページへ入ると team スコープへ切り替わる', async () => {
    const teamStore = useTeamStore()
    teamStore.myTeams = [team(12, 'alpha', '朝練チーム', 'あされん')]
    const scopeStore = useScopeStore()

    const changed = await useScopeRouteSync().syncFromPath('/teams/alpha/schedule')

    expect(changed).toBe(true)
    expect(scopeStore.current).toEqual({ type: 'team', id: '12', name: 'あされん' })
  })

  it('所属組織のページへ入ると organization スコープへ切り替わる', async () => {
    const orgStore = useOrganizationStore()
    orgStore.myOrganizations = [org(7, 'beta', 'ベータ連盟')]
    const scopeStore = useScopeStore()

    const changed = await useScopeRouteSync().syncFromPath('/organizations/beta')

    expect(changed).toBe(true)
    expect(scopeStore.current).toEqual({ type: 'organization', id: '7', name: 'ベータ連盟' })
  })

  it('スコープ外のパス（/admin/*）では現在スコープを解除しない', async () => {
    const orgStore = useOrganizationStore()
    orgStore.myOrganizations = [org(7, 'beta', 'ベータ連盟')]
    const sync = useScopeRouteSync()
    const scopeStore = useScopeStore()
    await sync.syncFromPath('/organizations/beta')

    const changed = await sync.syncFromPath('/admin/receipts')

    expect(changed).toBe(false)
    expect(scopeStore.current).toEqual({ type: 'organization', id: '7', name: 'ベータ連盟' })
  })

  it('所属していない slug では現在スコープを変えない', async () => {
    const teamStore = useTeamStore()
    const fetchSpy = vi.spyOn(teamStore, 'fetchMyTeams').mockResolvedValue(undefined)
    const scopeStore = useScopeStore()

    const changed = await useScopeRouteSync().syncFromPath('/teams/unknown')

    expect(changed).toBe(false)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(scopeStore.current.type).toBe('personal')
  })

  it('所属一覧が空のときの再フェッチはセッション中1度きり', async () => {
    const teamStore = useTeamStore()
    const fetchSpy = vi.spyOn(teamStore, 'fetchMyTeams').mockResolvedValue(undefined)
    const sync = useScopeRouteSync()

    await sync.syncFromPath('/teams/unknown')
    await sync.syncFromPath('/teams/unknown2')

    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('同じスコープへの再遷移では書き込まない', async () => {
    const teamStore = useTeamStore()
    teamStore.myTeams = [team(12, 'alpha', '朝練チーム')]
    const sync = useScopeRouteSync()
    await sync.syncFromPath('/teams/alpha')

    expect(await sync.syncFromPath('/teams/alpha/members')).toBe(false)
  })
})
