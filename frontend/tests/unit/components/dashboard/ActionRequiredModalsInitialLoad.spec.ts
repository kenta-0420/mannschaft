import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'

/**
 * 要対応モーダル（CirculationConfirmModal / SurveyAnswerModal）の
 * 「初回マウント時ロード」回帰テスト。
 *
 * 背景（実機E2Eで捕捉したバグ）:
 *   利用側（WidgetCommandCenter / ScopeActionRequiredWidget / WidgetNotices）は
 *   v-if="item" と visible=true を同時にセットするため、初回マウント時に visible の
 *   false→true「遷移」が発生しない。watch(() => props.visible) に immediate がないと
 *   初回だけロードが不発になり、回覧モーダルでは印鑑登録済みでも
 *   「印鑑が設定されていません」と誤表示された（/seals へのリクエストがゼロ）。
 *
 * 検証観点:
 *   MODAL-INIT-001: CirculationConfirmModal を visible=true で初回マウント → getSeals が呼ばれる
 *   MODAL-INIT-002: CirculationConfirmModal を visible=false でマウント → getSeals は呼ばれない
 *   MODAL-INIT-003: SurveyAnswerModal を visible=true で初回マウント → getSurvey が呼ばれる
 *   MODAL-INIT-004: SurveyAnswerModal を visible=false でマウント → getSurvey は呼ばれない
 */

const mockGetSeals = vi.fn()
vi.mock('~/composables/useSealApi', () => ({
  useSealApi: () => ({
    getSeals: mockGetSeals,
  }),
}))

const mockStampDocument = vi.fn()
vi.mock('~/composables/useCirculationApi', () => ({
  useCirculationApi: () => ({
    stampDocument: mockStampDocument,
  }),
}))

const mockGetSurvey = vi.fn()
vi.mock('~/composables/useSurveyApi', () => ({
  useSurveyApi: () => ({
    getSurvey: mockGetSurvey,
  }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    error: vi.fn(),
    success: vi.fn(),
    showError: vi.fn(),
    showSuccess: vi.fn(),
    showInfo: vi.fn(),
    showWarn: vi.fn(),
  }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正）。
  useAuthStore: () => ({
    currentUser: { id: 42 },
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
  }),
}))

const CirculationConfirmModal = (await import('~/components/dashboard/CirculationConfirmModal.vue')).default
const SurveyAnswerModal = (await import('~/components/dashboard/SurveyAnswerModal.vue')).default

const circulationItem = {
  id: '100',
  title: '回覧テスト',
  circulatedAt: '',
  deadline: null,
}

const surveyItem = {
  id: 7,
  title: 'アンケートテスト',
  deadline: null,
}

async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

describe('要対応モーダルの初回マウント時ロード（immediate watch 回帰）', () => {
  beforeEach(() => {
    mockGetSeals.mockReset().mockResolvedValue([])
    mockGetSurvey.mockReset().mockResolvedValue({ data: null })
  })

  it('MODAL-INIT-001: CirculationConfirmModal を visible=true で初回マウントすると印鑑ロードが走る', async () => {
    await mountSuspended(CirculationConfirmModal, {
      props: {
        visible: true,
        item: circulationItem,
        scopeType: 'TEAM' as const,
        scopeId: '1',
      },
    })
    await flush()

    expect(mockGetSeals).toHaveBeenCalledTimes(1)
    expect(mockGetSeals).toHaveBeenCalledWith(42)
  })

  it('MODAL-INIT-002: CirculationConfirmModal を visible=false でマウントしてもロードは走らない', async () => {
    await mountSuspended(CirculationConfirmModal, {
      props: {
        visible: false,
        item: circulationItem,
        scopeType: 'TEAM' as const,
        scopeId: '1',
      },
    })
    await flush()

    expect(mockGetSeals).not.toHaveBeenCalled()
  })

  it('MODAL-INIT-003: SurveyAnswerModal を visible=true で初回マウントするとアンケート詳細ロードが走る', async () => {
    await mountSuspended(SurveyAnswerModal, {
      props: {
        visible: true,
        item: surveyItem,
        scopeType: 'ORGANIZATION' as const,
        scopeId: '3',
      },
    })
    await flush()

    expect(mockGetSurvey).toHaveBeenCalledTimes(1)
    expect(mockGetSurvey).toHaveBeenCalledWith('ORGANIZATION', '3', 7)
  })

  it('MODAL-INIT-004: SurveyAnswerModal を visible=false でマウントしてもロードは走らない', async () => {
    await mountSuspended(SurveyAnswerModal, {
      props: {
        visible: false,
        item: surveyItem,
        scopeType: 'ORGANIZATION' as const,
        scopeId: '3',
      },
    })
    await flush()

    expect(mockGetSurvey).not.toHaveBeenCalled()
  })
})
