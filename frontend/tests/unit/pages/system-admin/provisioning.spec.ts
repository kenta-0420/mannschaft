import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * pages/system-admin/provisioning/index.vue の UT（柱②-2 販促プロビジョニング）。
 *
 * DataTable/Dialog を伴う PrimeVue 依存の重い mount を避け、`pages/market/index.spec.ts` の
 * 金型どおりソース文字列アサーションで主要分岐を固定する。
 */
const source = readFileSync(
  resolve(process.cwd(), 'app/pages/system-admin/provisioning/index.vue'),
  'utf8',
)

describe('/system-admin/provisioning 販促プロビジョニング管理画面', () => {
  it('SYSTEM_ADMIN 権限チェックを行う', () => {
    expect(source).toContain('authStore.isSystemAdmin')
    expect(source).toContain("provisioning.admin.noPermission")
  })

  it('組織/チーム作成は name/inviteEmail のみを送る（DTOにslug等は無い）', () => {
    expect(source).toContain('provisioningApi.createOrganization({')
    expect(source).toContain('provisioningApi.createTeam({')
    expect(source).toContain('name: formName.value.trim()')
    expect(source).toContain('inviteEmail: formInviteEmail.value.trim()')
  })

  it('招待一覧はGET .../invitationsを叩き、状態を日本語i18nラベルで表示する', () => {
    expect(source).toContain('provisioningApi.list()')
    expect(source).toContain('provisioning.admin.status.${status}')
  })

  it('再送・取消はPENDINGの招待にのみ表示し、API呼び出し後に一覧を再読込する', () => {
    expect(source).toContain("row.status === 'PENDING'")
    expect(source).toContain('provisioningApi.resend(row.id)')
    expect(source).toContain('provisioningApi.cancel(row.id)')
    expect(source).toContain('await load()')
  })

  it('作成/再送/取消のエラーはhandleApiError経由でBEエラーコード（PROV_003/PROV_011等）の文言を表示する', () => {
    expect(source).toContain('handleApiError(err, ')
    expect(source).not.toMatch(/catch\s*\(\s*\)\s*\{\s*\}/)
  })

  it('フォームは名称・招待メールの必須バリデーションを持つ', () => {
    expect(source).toContain('provisioning.admin.form.nameRequired')
    expect(source).toContain('provisioning.admin.form.emailRequired')
    expect(source).toContain('provisioning.admin.form.emailInvalid')
    expect(source).toContain('EMAIL_PATTERN')
  })
})
