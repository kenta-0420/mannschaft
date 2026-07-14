/**
 * useAppNavGroups のユニットテスト — サイドバー化 Phase1
 *
 * 受け入れ条件:
 * - AC4: navSettingsStore.visibleFeatures 全件がいずれかのグループに出力される（射影の網羅）
 * - AC6: navGroups 定義に無い未知キーは「その他」グループへフォールバックする
 * - AC7: 表示項目が空でも空配列を返し例外を投げない
 * - AC8: 代理入力デスクは NEIGHBORHOOD/CONDO × DEPUTY_ADMIN 以上の場合のみ表示される
 * - AC9: SYSTEM は isSystemAdmin の場合のみ表示される
 * - AC10: 同期は syncStore.hasConflicts の場合のみ、conflictCount 付きで表示される
 * - AC16: 空グループは出力から除外される
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppNavGroups } from '~/composables/useAppNavGroups'
import { useNavSettingsStore } from '~/stores/useNavSettingsStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useAuthStore } from '~/stores/useAuthStore'
import { useSyncStore } from '~/stores/useSyncStore'
import { useInboxStore } from '~/stores/useInboxStore'
import type { NavFeatureItem } from '~/types/nav'

function makeFeature(overrides: Partial<NavFeatureItem> = {}): NavFeatureItem {
  return {
    key: 'todo',
    labelKey: 'nav.todo',
    icon: 'pi pi-check',
    path: '/todos',
    fixed: false,
    sortOrder: 10,
    mobileVisible: true,
    visible: true,
    ...overrides,
  }
}

describe('useAppNavGroups', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('AC4: visibleFeatures 全件がいずれかのグループに出る（射影の網羅）', () => {
    it('nav_features マスタ相当の全 key が groups のいずれかに含まれる', () => {
      const navSettingsStore = useNavSettingsStore()
      const allKeys = [
        'calendar', 'settings', 'todo', 'shift-management', 'timeline', 'chat',
        'my-shift', 'my-page', 'qa', 'villages', 'blog',
        'reservations', 'wallet', 'inbox', 'market', 'jobs', 'matching', 'my-files',
      ]
      navSettingsStore.features = allKeys.map(key => makeFeature({ key, path: `/${key}`, visible: true }))

      const { groups } = useAppNavGroups()
      const groupedKeys = groups.value.flatMap(g => g.items.map(i => i.key))

      // 固定のダッシュボードを含め、visibleFeatures 全件が漏れなくいずれかのグループに出力される
      expect(groupedKeys).toContain('dashboard')
      for (const key of allKeys) {
        expect(groupedKeys).toContain(key)
      }
    })

    it('visible=false の項目は出力に含まれない', () => {
      const navSettingsStore = useNavSettingsStore()
      navSettingsStore.features = [
        makeFeature({ key: 'todo', visible: true }),
        makeFeature({ key: 'chat', visible: false }),
      ]

      const { groups } = useAppNavGroups()
      const groupedKeys = groups.value.flatMap(g => g.items.map(i => i.key))
      expect(groupedKeys).toContain('todo')
      expect(groupedKeys).not.toContain('chat')
    })
  })

  describe('AC6: 未知キーは「その他」グループへフォールバックする', () => {
    it('navGroups 定義に無い key は other グループに入る（項目消失しない）', () => {
      const navSettingsStore = useNavSettingsStore()
      navSettingsStore.features = [makeFeature({ key: 'brand-new-unmapped-feature', path: '/new' })]

      const { groups } = useAppNavGroups()
      const otherGroup = groups.value.find(g => g.key === 'other')
      expect(otherGroup).toBeDefined()
      expect(otherGroup?.items.map(i => i.key)).toContain('brand-new-unmapped-feature')
    })
  })

  describe('AC7 / AC16: 表示項目が空でも例外を投げず、空グループを出力しない', () => {
    it('visibleFeatures が空でも dashboard を含む home グループのみ出力され、例外を投げない', () => {
      const navSettingsStore = useNavSettingsStore()
      navSettingsStore.features = []

      let groups: ReturnType<typeof useAppNavGroups>['groups']
      expect(() => {
        groups = useAppNavGroups().groups
      }).not.toThrow()

      // dashboard は固定項目として必ず home グループに1件だけ出力される
      expect(groups!.value.length).toBeGreaterThan(0)
      const nonEmptyGroups = groups!.value.every(g => g.items.length > 0)
      expect(nonEmptyGroups).toBe(true)

      const homeGroup = groups!.value.find(g => g.key === 'home')
      expect(homeGroup?.items.map(i => i.key)).toEqual(['dashboard'])

      // 条件付き項目（proxy-desk/system-admin/sync）を出す条件が全て false のため
      // living/work/account/admin/other グループは出力されない
      expect(groups!.value.map(g => g.key)).toEqual(['home'])
    })
  })

  describe('AC8: 代理入力デスク — NEIGHBORHOOD/CONDO × DEPUTY_ADMIN 以上でのみ表示', () => {
    it('NEIGHBORHOOD テンプレート×DEPUTY_ADMIN のチームがあれば表示される（admin グループ）', () => {
      const teamStore = useTeamStore()
      teamStore.myTeams = [
        { id: 1, slug: 't1', name: 'T1', nickname1: null, iconUrl: null, role: 'DEPUTY_ADMIN', template: 'NEIGHBORHOOD', memberCount: 1 },
      ]

      const { groups, showProxyDeskNav } = useAppNavGroups()
      expect(showProxyDeskNav.value).toBe(true)
      const adminGroup = groups.value.find(g => g.key === 'admin')
      expect(adminGroup?.items.map(i => i.key)).toContain('proxy-desk')
    })

    it('CONDO テンプレート×MEMBER のみのチームでは表示されない（境界: DEPUTY_ADMIN未満）', () => {
      const teamStore = useTeamStore()
      teamStore.myTeams = [
        { id: 1, slug: 't1', name: 'T1', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'CONDO', memberCount: 1 },
      ]

      const { groups, showProxyDeskNav } = useAppNavGroups()
      expect(showProxyDeskNav.value).toBe(false)
      const groupedKeys = groups.value.flatMap(g => g.items.map(i => i.key))
      expect(groupedKeys).not.toContain('proxy-desk')
    })

    it('テンプレートが NEIGHBORHOOD/CONDO 以外なら ADMIN でも表示されない', () => {
      const teamStore = useTeamStore()
      teamStore.myTeams = [
        { id: 1, slug: 't1', name: 'T1', nickname1: null, iconUrl: null, role: 'ADMIN', template: 'SPORTS_CLUB', memberCount: 1 },
      ]

      const { showProxyDeskNav } = useAppNavGroups()
      expect(showProxyDeskNav.value).toBe(false)
    })
  })

  describe('AC9: SYSTEM — isSystemAdmin の場合のみ表示', () => {
    it('systemRole=SYSTEM_ADMIN のとき SYSTEM が admin グループに出る', () => {
      const authStore = useAuthStore()
      // @ts-expect-error テスト用に private相当のstateへ直接代入
      authStore.user = { id: 1, systemRole: 'SYSTEM_ADMIN' }

      const { groups } = useAppNavGroups()
      const adminGroup = groups.value.find(g => g.key === 'admin')
      expect(adminGroup?.items.map(i => i.key)).toContain('system-admin')
    })

    it('systemRole が無ければ SYSTEM は出ない', () => {
      const { groups } = useAppNavGroups()
      const groupedKeys = groups.value.flatMap(g => g.items.map(i => i.key))
      expect(groupedKeys).not.toContain('system-admin')
    })
  })

  describe('AC10: 同期 — hasConflicts の場合のみ conflictCount 付きで表示', () => {
    it('コンフリクトがあれば sync 項目が badgeCount=件数 付きで account グループに出る', () => {
      const syncStore = useSyncStore()
      syncStore.conflicts = [
        { clientId: 'c1', path: '/a', message: 'x' },
        { clientId: 'c2', path: '/b', message: 'y' },
      ]

      const { groups, showSyncNav } = useAppNavGroups()
      expect(showSyncNav.value).toBe(true)
      const accountGroup = groups.value.find(g => g.key === 'account')
      const syncItem = accountGroup?.items.find(i => i.key === 'sync')
      expect(syncItem).toBeDefined()
      expect(syncItem?.badgeCount).toBe(2)
    })

    it('コンフリクトが無ければ sync 項目は出ない', () => {
      const { groups, showSyncNav } = useAppNavGroups()
      expect(showSyncNav.value).toBe(false)
      const groupedKeys = groups.value.flatMap(g => g.items.map(i => i.key))
      expect(groupedKeys).not.toContain('sync')
    })
  })

  describe('Phase2 AC-21: 受信箱バッジ — inboxStore.inboxCount が inbox 項目の badgeCount に結線される', () => {
    it('inboxStore.summaryByState.INBOX 件数が inbox 項目の badgeCount に反映される', () => {
      const navSettingsStore = useNavSettingsStore()
      navSettingsStore.features = [makeFeature({ key: 'inbox', path: '/inbox' })]
      const inboxStore = useInboxStore()
      inboxStore.summaryByState = { INBOX: 7 }

      const { groups } = useAppNavGroups()
      const groupedItems = groups.value.flatMap(g => g.items)
      const inboxItem = groupedItems.find(i => i.key === 'inbox')
      expect(inboxItem?.badgeCount).toBe(7)
    })

    it('件数0のときは badgeCount が undefined 相当になり、他項目は影響を受けない', () => {
      const navSettingsStore = useNavSettingsStore()
      navSettingsStore.features = [
        makeFeature({ key: 'inbox', path: '/inbox' }),
        makeFeature({ key: 'todo', path: '/todos' }),
      ]

      const { groups } = useAppNavGroups()
      const groupedItems = groups.value.flatMap(g => g.items)
      const inboxItem = groupedItems.find(i => i.key === 'inbox')
      const todoItem = groupedItems.find(i => i.key === 'todo')
      expect(inboxItem?.badgeCount).toBe(0)
      expect(todoItem?.badgeCount).toBeUndefined()
    })
  })
})
