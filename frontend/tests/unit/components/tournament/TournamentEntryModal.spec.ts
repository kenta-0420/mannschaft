import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TournamentEntryModal from '~/components/TournamentEntryModal.vue'

/**
 * CMP-260929-0654: 大会エントリー管理モーダルの回帰。
 *
 *   ENT-001: 親の v-if により isOpen=true で生成されても、取得関数（entry-members / entry-templates）が呼ばれる
 *   ENT-002: BE の { data: ... } 包みを剥がし、メンバーとテンプレートが描画される
 *   ENT-003: 非管理者はテンプレート一覧を取得しない
 *   ENT-004: 閉じるボタンは存在する i18n キー button.close を参照する
 *   ENT-005: テンプレート適用の { data } 包みの応答を剥がして一覧に反映する
 *   ENT-006: 保存（upsert）の { data } 包みの応答を剥がして一覧に反映する
 *   ENT-007: チームから一括ロードの { data } 包みの応答を剥がして一覧に反映する
 *
 * Dialog は Teleport で body 直下に描画されるため document.body から探す。
 */

const getEntryMembers = vi.fn()
const getEntryTemplates = vi.fn()
const applyEntryTemplate = vi.fn()
const upsertEntryMembers = vi.fn()
const loadEntryMembersFromTeam = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTournamentApi', () => () => ({
  getEntryMembers,
  loadEntryMembersFromTeam,
  upsertEntryMembers,
  downloadEntryPdf: vi.fn(),
  getEntryTemplates,
  applyEntryTemplate,
}))

const props = {
  isOpen: true,
  orgId: 'org-000009',
  tournamentId: 12,
  divisionId: 5,
  participantId: 31,
  teamId: '7',
  isAdmin: true,
}

const membersRes = {
  data: {
    entryMembers: [
      {
        id: 'em-1',
        participantId: 31,
        userId: 1,
        displayName: '山田太郎',
        memberNumber: null,
        position: 'GK',
        jerseyNumber: 1,
        notes: null,
        sortOrder: 1,
        createdAt: '',
        updatedAt: '',
      },
    ],
    teamMemberCandidates: [
      { userId: 1, displayName: '山田太郎', memberNumber: null, position: 'GK', isAlreadyEntered: true },
      { userId: 2, displayName: '佐藤次郎', memberNumber: null, position: null, isAlreadyEntered: false },
    ],
    entryCount: 1,
    minEntryCount: null,
    maxEntryCount: null,
  },
}

/** 変更系 API が返す一覧（{ data } 包み）。鈴木三郎が増えている */
function member(id: string, userId: number, displayName: string) {
  return {
    id, participantId: 31, userId, displayName, memberNumber: null, position: null,
    jerseyNumber: null, notes: null, sortOrder: userId, createdAt: '', updatedAt: '',
  }
}
const afterEntryMembers = [member('em-1', 1, '山田太郎'), member('em-3', 3, '鈴木三郎')]

function findButton(label: string): HTMLButtonElement {
  const btn = Array.from(document.body.querySelectorAll('button')).find(b => b.textContent?.includes(label))
  if (!btn) throw new Error(`ボタン "${label}" が見つかりません`)
  return btn as HTMLButtonElement
}

const templatesRes = {
  data: [
    { id: 't-1', name: '準決勝用ベストメンバー', description: null, sortOrder: 1, memberCount: 11, updatedAt: '' },
  ],
}

async function mountModal(override: Partial<typeof props> = {}) {
  const wrapper = await mountSuspended(TournamentEntryModal, { props: { ...props, ...override } })
  await flushPromises()
  return wrapper
}

describe('TournamentEntryModal.vue', () => {
  beforeEach(() => {
    getEntryMembers.mockReset()
    getEntryTemplates.mockReset()
    getEntryMembers.mockResolvedValue(membersRes)
    getEntryTemplates.mockResolvedValue(templatesRes)
    applyEntryTemplate.mockReset()
    upsertEntryMembers.mockReset()
    loadEntryMembersFromTeam.mockReset()
  })

  it('ENT-001: isOpen=true で生成された時点で entry-members と entry-templates を取得する', async () => {
    await mountModal()

    expect(getEntryMembers).toHaveBeenCalledWith('org-000009', 12, 5, 31, true)
    expect(getEntryTemplates).toHaveBeenCalledWith('org-000009', '7')
  })

  it('ENT-002: { data } 包みを剥がしてメンバー名とテンプレート選択を描画する', async () => {
    await mountModal()

    expect(document.body.textContent).toContain('山田太郎')
    expect(document.body.textContent).not.toContain('tournament.entry.template.noTemplates')
  })

  it('ENT-003: 非管理者はテンプレート一覧を取得しない', async () => {
    await mountModal({ isAdmin: false })

    expect(getEntryMembers).toHaveBeenCalledWith('org-000009', 12, 5, 31, false)
    expect(getEntryTemplates).not.toHaveBeenCalled()
  })

  it('ENT-004: 閉じるボタンは翻訳済みの文言を出す（common.close は存在しないキー）', async () => {
    await mountModal()

    // テンプレートの $t は実 i18n。キーが存在しなければキー文字列がそのまま出る
    expect(document.body.textContent).toContain('Close')
    expect(document.body.textContent).not.toContain('common.close')
  })

  it('ENT-005: テンプレート適用の { data } 応答を剥がして一覧に反映する', async () => {
    applyEntryTemplate.mockResolvedValue({
      data: { applied: 1, skipped: 0, skippedInactive: 0, total: 2, entryMembers: afterEntryMembers },
    })
    const wrapper = await mountModal()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(wrapper.vm as any).selectedTemplateId = 't-1'
    await flushPromises()

    findButton('tournament.entry.template.apply').click()
    await flushPromises()

    expect(applyEntryTemplate).toHaveBeenCalledWith(
      'org-000009', 12, 5, 31, { templateId: 't-1', overwriteExisting: false },
    )
    expect(document.body.textContent).toContain('鈴木三郎')
  })

  it('ENT-006: 保存の { data } 応答を剥がして一覧に反映する', async () => {
    upsertEntryMembers.mockResolvedValue({
      data: { entryMembers: afterEntryMembers, entryCount: 2, minEntryCount: null, maxEntryCount: null },
    })
    await mountModal()

    const saveButtons = Array.from(document.body.querySelectorAll('button'))
      .filter(b => b.textContent?.includes('tournament.entry.saveMembers'))
    saveButtons[saveButtons.length - 1]!.click()
    await flushPromises()

    expect(upsertEntryMembers).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('鈴木三郎')
  })

  it('ENT-007: チームから一括ロードの { data } 応答を剥がして一覧に反映する', async () => {
    loadEntryMembersFromTeam.mockResolvedValue({
      data: { added: 1, skipped: 0, total: 2, entryMembers: afterEntryMembers },
    })
    await mountModal()

    findButton('tournament.entry.loadFromTeam').click()
    await flushPromises()

    expect(loadEntryMembersFromTeam).toHaveBeenCalledWith('org-000009', 12, 5, 31, { overwriteExisting: false })
    expect(document.body.textContent).toContain('鈴木三郎')
  })
})
