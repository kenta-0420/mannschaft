import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import systemAdminMiddleware from '~/middleware/system-admin'

/**
 * CMP-260930-1933（ALIC-1）: 一般会員でも /system-admin 配下が描画されてしまう欠陥の固定。
 *
 * AC-1 SYSTEM_ADMIN は素通り（navigateTo も toast も無し）
 * AC-2 一般会員（ログイン済み・非 SYSTEM_ADMIN）は /dashboard へ誘導し、error トースト1回
 * AC-3 未認証は auth ミドルウェアの担当のためここでは何もしない
 * AC-4 /system-admin 配下の全ページが middleware: ['auth', 'system-admin'] を指定している（漏れ防止の番人）
 *
 * useNuxtApp は差し替えない（feature-gate.global.spec.ts と同じ理由）。$toast だけ用意する。
 */

const mockNavigateTo = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigateTo(...args))

let authStub: { isAuthenticated: boolean; isSystemAdmin: boolean }
mockNuxtImport('useAuthStore', () => () => authStub)

const route = { path: '/system-admin', fullPath: '/system-admin' } as unknown as RouteLocationNormalized

let sharedToastAdd: ReturnType<typeof vi.fn> | undefined

function installToast(): ReturnType<typeof vi.fn> {
  const nuxtApp = useNuxtApp()
  const holder = nuxtApp as unknown as Record<string, unknown>
  if (!sharedToastAdd) {
    sharedToastAdd = vi.fn()
    const existing = holder.$toast as { add?: unknown } | undefined
    if (existing && typeof existing.add === 'function') {
      ;(existing as { add: unknown }).add = sharedToastAdd
    } else {
      nuxtApp.provide('toast', { add: sharedToastAdd })
    }
  }
  sharedToastAdd.mockReset()
  return sharedToastAdd
}

describe('system-admin ミドルウェア', () => {
  beforeEach(() => {
    mockNavigateTo.mockReset()
    authStub = { isAuthenticated: true, isSystemAdmin: true }
  })

  afterEach(() => {
    sharedToastAdd?.mockReset()
  })

  it('(AC-1) SYSTEM_ADMIN は素通りする', async () => {
    const toastAdd = installToast()
    const result = await systemAdminMiddleware(route, route)
    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()
  })

  it('(AC-2) 一般会員は /dashboard へ誘導され、error トーストが1回出る', async () => {
    const toastAdd = installToast()
    authStub = { isAuthenticated: true, isSystemAdmin: false }
    await systemAdminMiddleware(route, route)
    expect(mockNavigateTo).toHaveBeenCalledTimes(1)
    expect(mockNavigateTo).toHaveBeenCalledWith('/dashboard')
    expect(toastAdd).toHaveBeenCalledTimes(1)
    expect(toastAdd.mock.calls[0]![0]).toMatchObject({ severity: 'error' })
  })

  it('(AC-3) 未認証は auth ミドルウェアの担当のため何もしない', async () => {
    const toastAdd = installToast()
    authStub = { isAuthenticated: false, isSystemAdmin: false }
    const result = await systemAdminMiddleware(route, route)
    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()
  })
})

describe('system-admin 配下ページのガード網羅（番人）', () => {
  function collectVueFiles(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
      const full = join(dir, name)
      return statSync(full).isDirectory() ? collectVueFiles(full) : full.endsWith('.vue') ? [full] : []
    })
  }

  it('(AC-4) 全ページが middleware: [\'auth\', \'system-admin\'] を指定している', () => {
    const root = join(process.cwd(), 'app', 'pages', 'system-admin')
    const files = collectVueFiles(root)
    expect(files.length).toBeGreaterThan(0)
    const unguarded = files.filter(
      (f) => !/middleware:\s*\[\s*'auth'\s*,\s*'system-admin'\s*\]/.test(readFileSync(f, 'utf8')),
    )
    expect(unguarded).toEqual([])
  })
})
