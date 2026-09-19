import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練D（第5隊）AC-125 ページレベルの結線（PR6a と同型の欠陥の再発防止・裏取り）。
 *
 * `BillingManagePanel.planChange.spec.ts` は `BillingManagePanel` を USER/TEAM/ORG の
 * 3スコープで直接 `mountSuspended` し、プラン変更ボタン→ダイアログの結線を測っている。
 * ただしそれだけでは「パネル自体は正しいが、実際のページから参照されていない」という
 * PR6a 型の欠陥（新しい解約ダイアログがどの画面からも参照されていなかった）を検出できない。
 *
 * この3ページ（`settings/billing.vue` / `teams/[slug]/settings/billing.vue` /
 * `organizations/[slug]/settings/billing.vue`）は `useRoute` / `useRoleAccess` を
 * 実行時に要求するため `mountSuspended` での結線検証はモック整備コストが高い。
 * そこで実際に本番ビルドへ入るソースコード自体を検査し、各ページが
 * `<BillingManagePanel>` を対応する scope-kind と共にテンプレートへ実際に置いていることを
 * 直接固定する（`admin/receipts.spec.ts` と同型のソーステキスト検査パターン）。
 */

function readSource(relativePath: string): string {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8').replace(/\r\n/g, '\n')
}

describe.each([
  {
    label: 'USER (settings/billing.vue)',
    path: 'app/pages/settings/billing.vue',
    scopeKindAttr: 'scope-kind="USER"',
  },
  {
    label: 'TEAM (teams/[slug]/settings/billing.vue)',
    path: 'app/pages/teams/[slug]/settings/billing.vue',
    scopeKindAttr: 'scope-kind="TEAM"',
  },
  {
    label: 'ORG (organizations/[slug]/settings/billing.vue)',
    path: 'app/pages/organizations/[slug]/settings/billing.vue',
    scopeKindAttr: 'scope-kind="ORG"',
  },
])('AC-125 ページ結線: $label', ({ path, scopeKindAttr }) => {
  it('BillingManagePanel を対応する scope-kind と共に実際にテンプレートへ置いている', () => {
    const source = readSource(path)
    expect(source).toContain('<BillingManagePanel')
    expect(source).toContain(scopeKindAttr)
  })

  it('BillingManagePanel が v-if 等で常時レンダリングを妨げられていない（コメントアウト・条件分岐で隠されていない）', () => {
    const source = readSource(path)
    const templateMatch = source.match(/<template>[\s\S]*<\/template>/)
    expect(templateMatch).not.toBeNull()
    const template = templateMatch![0]
    const panelLine = template
      .split('\n')
      .find(line => line.includes('<BillingManagePanel'))
    expect(panelLine).toBeDefined()
    expect(panelLine).not.toMatch(/v-if=/)
    expect(panelLine).not.toMatch(/<!--/)
  })
})
