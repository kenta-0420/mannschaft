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
 *
 * Dialog は Teleport で body 直下に描画されるため document.body から探す。
 */

const getEntryMembers = vi.fn()
const getEntryTemplates = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTournamentApi', () => () => ({
  getEntryMembers,
  loadEntryMembersFromTeam: vi.fn(),
  upsertEntryMembers: vi.fn(),
  downloadEntryPdf: vi.fn(),
  getEntryTemplates,
  applyEntryTemplate: vi.fn(),
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
    entryCount: 1,
    minEntryCount: null,
    maxEntryCount: null,
  },
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

  it('ENT-004: 閉じるボタンのラベルは button.close（common.close は存在しないキー）', async () => {
    await mountModal()

    expect(document.body.textContent).toContain('button.close')
    expect(document.body.textContent).not.toContain('common.close')
  })
})
