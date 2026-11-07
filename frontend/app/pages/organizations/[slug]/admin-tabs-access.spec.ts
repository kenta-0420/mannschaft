import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'
import adminConsole from '~/middleware/admin-console'
import orgRoleGuard from '~/middleware/org-role-guard'

/**
 * CMP-260917-1351: HTTP 200 / roleName=null は、読込中でも管理権限でもない。
 * ページが実際に宣言するガードの結線と、既存ガードの非同期境界を組み合わせて検証する。
 * 未所属 null の2件が修正前の red 対象。実機の越境 GET 認可はこのモック試験では代替しない。
 */
const navigate = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigate(...args))

type LoadResult = { ok: true } | { ok: false, error: Error }
let role: Ref<string | null>
let access: {
  loadPermissions: ReturnType<typeof vi.fn<() => Promise<LoadResult>>>
  isAdmin: ComputedRef<boolean>
  isAdminOrDeputy: ComputedRef<boolean>
}
mockNuxtImport('useRoleAccess', () => () => access)

const top = '/organizations/null-role-org'
const pages = [
  { name: 'invites', guard: adminConsole, deputyAllowed: true },
  { name: 'permission-groups', guard: orgRoleGuard, deputyAllowed: false },
] as const

function route(page: string): RouteLocationNormalized {
  return {
    path: `${top}/${page}`,
    fullPath: `${top}/${page}`,
    hash: '',
    params: { slug: 'null-role-org' },
    query: {},
    name: undefined,
    matched: [],
    meta: {},
    redirectedFrom: undefined,
  }
}

async function runDeclaredGuards(page: 'invites' | 'permission-groups') {
  const source = readFileSync(resolve(process.cwd(), 'app/pages/organizations/[slug]', `${page}.vue`), 'utf8')
  const declaration = source.match(/middleware:\s*(\[[^\]]*\]|'[^']*')/)
  expect(declaration, '対象ページに middleware 宣言が必要').not.toBeNull()
  const names = [...declaration![1]!.matchAll(/'([^']+)'/g)].map(match => match[1])
  for (const name of names) {
    if (name === 'auth') continue // 認証済み利用者のスコープ境界だけを扱う。
    const guard = name === 'admin-console' ? adminConsole : name === 'org-role-guard' ? orgRoleGuard : null
    expect(guard, `未検証の middleware: ${name}`).not.toBeNull()
    await guard!(route(page), route(page))
  }
}

beforeEach(() => {
  navigate.mockReset()
  role = ref<string | null>(null)
  access = {
    loadPermissions: vi.fn<() => Promise<LoadResult>>().mockResolvedValue({ ok: true }),
    isAdmin: computed(() => role.value === 'ADMIN' || role.value === 'SYSTEM_ADMIN'),
    isAdminOrDeputy: computed(() => ['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'].includes(role.value ?? '')),
  }
})

describe.each(pages)('$name の null ロール境界', ({ name, guard, deputyAllowed }) => {
  it('正常に解決した未所属 null を、ページに結線されたガードが組織トップへ戻す', async () => {
    await runDeclaredGuards(name)
    expect(navigate).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledWith(top)
  })

  it.each(['ADMIN', 'DEPUTY_ADMIN', 'MEMBER'])('%s の既存許可範囲を維持する', async (value) => {
    role.value = value
    await guard(route(name), route(name))
    if (value === 'ADMIN' || (value === 'DEPUTY_ADMIN' && deputyAllowed)) {
      expect(navigate).not.toHaveBeenCalled()
    }
    else {
      expect(navigate).toHaveBeenCalledWith(top)
    }
  })

  it('取得前の null では退避せず、正常解決後の null で初めて退避する', async () => {
    let resolve!: (result: LoadResult) => void
    access.loadPermissions.mockReturnValue(new Promise<LoadResult>((done) => { resolve = done }))
    const pending = guard(route(name), route(name))
    expect(access.loadPermissions).toHaveBeenCalledOnce()
    expect(navigate).not.toHaveBeenCalled()
    resolve({ ok: true })
    await pending
    expect(navigate).toHaveBeenCalledWith(top)
  })

  it('取得失敗を未所属と混同せず、redirect ではなく 503 として伝える', async () => {
    access.loadPermissions.mockResolvedValue({ ok: false, error: new Error('権限取得失敗') })
    await expect(guard(route(name), route(name))).rejects.toMatchObject({ statusCode: 503, fatal: true })
    expect(navigate).not.toHaveBeenCalled()
  })
})
