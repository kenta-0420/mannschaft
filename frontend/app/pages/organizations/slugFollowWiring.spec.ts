import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * CMP-261001-0835 検分修繕③: `pages/organizations/[slug].vue` の
 * フォロー（サポーター）結線をソーステキストで固定する。
 *
 * `useFollowSelfStatus.spec.ts` は composable 単体の fail-close 挙動を検証するが、
 * それだけでは「composable 自体は正しいが、ページがロール有無でガードして一度も
 * 呼んでいない」「解除成功後に loadPermissions を渡していない」といった結線の欠落を
 * 検出できない。`useOrgDetail`/`useRoleAccess` は `useRoute` 等の実行時依存が重く、
 * mountSuspended でのフル結線検証は（CLAUDE.md の例外節のとおり）整備コストが
 * 高すぎるため、実ビルドへ入るソースコードそのものを検査する
 * （`billing.pagesWiring.spec.ts` と同型のソーステキスト検査パターン）。
 */
function readSource(): string {
  return readFileSync(resolve(process.cwd(), 'app/pages/organizations/[slug].vue'), 'utf8')
    .replace(/\r\n/g, '\n')
}

describe('pages/organizations/[slug].vue フォロー結線（CMP-261001-0835 検分修繕）', () => {
  it('AC-5: fetchFollowStatus はシェルデータロードで roleName によるガードを経由せず常に呼ばれる', () => {
    const source = readSource()
    const loadShellDataMatch = source.match(/async function loadShellData\(\)[\s\S]*?\n}/)
    expect(loadShellDataMatch).not.toBeNull()
    const body = loadShellDataMatch![0]
    expect(body).toContain('fetchFollowStatus()')
    // fetchFollowStatus() の呼び出し前に roleName による早期 return ガードが無いこと
    // （旧実装の fail-open: `if (roleName.value) return` で SUPPORTER 自身の状態が
    //   永遠に取得されなかった不具合の再発防止）。
    const beforeFetch = body.slice(0, body.indexOf('fetchFollowStatus()'))
    expect(beforeFetch).not.toMatch(/if\s*\(\s*roleName\.value\s*\)\s*return/)
  })

  it('AC-7/AC-9: フォロー解除は loadPermissions を渡して呼ぶ（権限再取得コールバック結線）', () => {
    const source = readSource()
    expect(source).toMatch(/async function handleCancelSupporter\(\)[\s\S]*?await cancelSupporter\(loadPermissions\)/)
  })

  it('AC-9: 権限再取得のみの再試行導線も loadPermissions を渡して呼ぶ', () => {
    const source = readSource()
    expect(source).toMatch(
      /async function handleRetryFollowPermissionSync\(\)[\s\S]*?await retryFollowPermissionSync\(loadPermissions\)/,
    )
  })

  it('OrgPageHeader へ followStatus 系 props と cancel-supporter 系イベントが実際に結線されている', () => {
    const source = readSource()
    const templateMatch = source.match(/<template>[\s\S]*<\/template>/)
    expect(templateMatch).not.toBeNull()
    const template = templateMatch![0]
    expect(template).toMatch(/<OrgPageHeader[\s\S]*?\/?>|<OrgPageHeader[\s\S]*?<\/OrgPageHeader>/)
    const headerBlockMatch = template.match(/<OrgPageHeader[\s\S]*?(?:\/>|<\/OrgPageHeader>)/)
    expect(headerBlockMatch).not.toBeNull()
    const headerBlock = headerBlockMatch![0]
    expect(headerBlock).toContain(':follow-status="followStatus"')
    expect(headerBlock).toContain(':follow-permission-sync-error="followPermissionSyncError"')
    expect(headerBlock).toMatch(/@cancel-supporter="[^"]*(?:handleCancelSupporter|showCancelConfirm)[^"]*"|@show-cancel-confirm="[^"]*"/)
    expect(headerBlock).toMatch(/@retry-follow-permission-sync="handleRetryFollowPermissionSync"/)
  })
})
