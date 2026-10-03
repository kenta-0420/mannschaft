import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * CMP-261001-0835 検分修繕③: `pages/teams/[slug].vue` の
 * フォロー（サポーター）結線をソーステキストで固定する。
 * `pages/organizations/slugFollowWiring.spec.ts` と同型（組織・チーム双子の AC）。
 */
function readSource(): string {
  return readFileSync(resolve(process.cwd(), 'app/pages/teams/[slug].vue'), 'utf8')
    .replace(/\r\n/g, '\n')
}

describe('pages/teams/[slug].vue フォロー結線（CMP-261001-0835 検分修繕）', () => {
  it('AC-5: fetchFollowStatus はシェルデータロードで roleName によるガードを経由せず常に呼ばれる', () => {
    const source = readSource()
    const loadShellDataMatch = source.match(/async function loadShellData\(\)[\s\S]*?\n}/)
    expect(loadShellDataMatch).not.toBeNull()
    const body = loadShellDataMatch![0]
    expect(body).toContain('fetchFollowStatus()')
    const beforeFetch = body.slice(0, body.indexOf('fetchFollowStatus()'))
    expect(beforeFetch).not.toMatch(/if\s*\(\s*roleName\.value\s*\)\s*return/)
  })

  it('AC-7/AC-9: フォロー解除は loadPermissions を渡して呼ぶ（権限再取得コールバック結線）', () => {
    const source = readSource()
    expect(source).toMatch(/async function cancelSupporter\(\)[\s\S]*?await cancelSupporterRaw\(teamSlug\.value, loadPermissions\)/)
  })

  it('AC-9: 権限再取得のみの再試行導線も loadPermissions を渡して呼ぶ', () => {
    const source = readSource()
    expect(source).toMatch(
      /async function retryFollowPermissionSync\(\)[\s\S]*?await retryFollowPermissionSyncRaw\(loadPermissions\)/,
    )
  })

  it('TeamPageHeader へ followStatus 系 props と cancel-supporter 系イベントが実際に結線されている', () => {
    const source = readSource()
    const templateMatch = source.match(/<template>[\s\S]*<\/template>/)
    expect(templateMatch).not.toBeNull()
    const template = templateMatch![0]
    const headerBlockMatch = template.match(/<TeamPageHeader[\s\S]*?(?:\/>|<\/TeamPageHeader>)/)
    expect(headerBlockMatch).not.toBeNull()
    const headerBlock = headerBlockMatch![0]
    expect(headerBlock).toContain(':follow-status="followStatus"')
    expect(headerBlock).toContain(':follow-permission-sync-error="followPermissionSyncError"')
    expect(headerBlock).toMatch(/@cancel-supporter="cancelSupporter"/)
    expect(headerBlock).toMatch(/@retry-follow-permission-sync="retryFollowPermissionSync"/)
    expect(headerBlock).toMatch(/@show-cancel-confirm="showCancelSupporterConfirm = true"/)
  })
})
