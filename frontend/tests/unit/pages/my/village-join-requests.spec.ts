import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import VillageJoinRequestsPage from '~/pages/my/village-join-requests.vue'
import type { MyJoinRequestResponse } from '~/types/village'

/**
 * CMP-260826-1456 Codex 検分差し戻しの根治テスト。
 *
 * メッセージを付けずに複数の村へ申請すると、履歴の各行から申請先の村を識別できなかった。
 * 本人が自分で申請した村に限り BE が返す villageName / villageState を各行に表示する。
 *
 * 検証観点:
 *   MJ-001 複数の村へ申請した履歴で、メッセージ無しでも行ごとに別の村名が出る
 *   MJ-002 凍結済み・削除済みの村は名前を保ったまま状態タグを出し、通常の村にはタグを出さない
 *   MJ-003 村の行が存在せず名前が無い場合は「確認できません」の代替表示にする
 */

function row(
  id: string,
  villageName: string | null,
  villageState: MyJoinRequestResponse['villageState'],
  status: MyJoinRequestResponse['status'],
): MyJoinRequestResponse {
  return {
    id,
    villageId: `village-of-${id}`,
    villageName,
    villageState,
    subjectType: 'USER',
    subjectId: 1,
    message: null,
    status,
    reviewedBy: null,
    reviewedAt: null,
    reviewComment: null,
    createdAt: '2026-08-26T12:00:00',
  }
}

/**
 * テスト環境のロケールは navigator 言語に引きずられ英語になりうるため、本アプリの既定ロケール(ja)へ
 * 明示的に切り替えてから描画を確かめる（DashboardScopeAccordion.spec.ts と同じ setLocale + 待機の作法）。
 */
async function mountJa() {
  const wrapper = await mountSuspended(VillageJoinRequestsPage)
  const i18n = wrapper.vm.$i18n as { locale: string; setLocale?: (locale: string) => Promise<void> }
  if (i18n.setLocale) await i18n.setLocale('ja')
  else i18n.locale = 'ja'
  for (let i = 0; i < 40; i++) {
    await wrapper.vm.$nextTick()
    if (wrapper.text().includes('申請先の村')) break
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  return wrapper
}

const requests = ref<MyJoinRequestResponse[]>([])
vi.mock('~/composables/village/useVillageJoinRequestHistory', () => ({
  useVillageJoinRequestHistory: () => ({
    requests,
    page: ref(0),
    size: ref(20),
    meta: ref({ total: requests.value.length, page: 0, size: 20, totalPages: 1 }),
    loading: ref(false),
    error: ref(null),
    load: vi.fn(),
  }),
}))

beforeAll(async () => {
  requests.value = []
  const warmup = await mountSuspended(VillageJoinRequestsPage)
  warmup.unmount()
})

describe('pages/my/village-join-requests.vue — 申請先の村の識別', () => {
  it('MJ-001: 複数の村へ申請した履歴で、メッセージ無しでも行ごとに別の村名が出る', async () => {
    requests.value = [
      row('r1', '桜村', 'ACTIVE', 'APPROVED'),
      row('r2', '梅村', 'ACTIVE', 'REJECTED'),
    ]
    const wrapper = await mountJa()

    expect(wrapper.find('[data-testid="my-village-join-request-village-r1"]').text()).toContain('桜村')
    expect(wrapper.find('[data-testid="my-village-join-request-village-r2"]').text()).toContain('梅村')
    expect(wrapper.find('[data-testid="my-village-join-request-r1"]').text()).not.toContain('梅村')
    wrapper.unmount()
  })

  it('MJ-002: 凍結済み・削除済みの村は名前を保ったまま状態を示し、通常の村には出さない', async () => {
    requests.value = [
      row('r1', '桜村', 'ACTIVE', 'PENDING'),
      row('r2', '梅村', 'ARCHIVED', 'APPROVED'),
      row('r3', '松村', 'DELETED', 'WITHDRAWN'),
    ]
    const wrapper = await mountJa()

    const active = wrapper.find('[data-testid="my-village-join-request-village-r1"]').text()
    const archived = wrapper.find('[data-testid="my-village-join-request-village-r2"]').text()
    const deleted = wrapper.find('[data-testid="my-village-join-request-village-r3"]').text()
    expect(active).not.toMatch(/凍結済み|削除済み/)
    expect(archived).toContain('梅村')
    expect(archived).toContain('凍結済み')
    expect(deleted).toContain('松村')
    expect(deleted).toContain('削除済み')
    wrapper.unmount()
  })

  it('MJ-003: 村の行が無く名前が null の場合は代替表示にする', async () => {
    requests.value = [row('r1', null, 'DELETED', 'PENDING')]
    const wrapper = await mountJa()

    const text = wrapper.find('[data-testid="my-village-join-request-village-r1"]').text()
    expect(text).toContain('村の名前を確認できません')
    expect(text).toContain('削除済み')
    wrapper.unmount()
  })
})
