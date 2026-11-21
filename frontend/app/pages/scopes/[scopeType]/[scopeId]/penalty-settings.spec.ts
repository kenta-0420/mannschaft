import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { components } from '~/types/generated'
import PenaltySettingsPage from './penalty-settings.vue'

const api = vi.hoisted(() => ({
  getPenaltySetting: vi.fn(),
  getScopePenalties: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { scopeType: 'TEAM', scopeId: '42' } }))
mockNuxtImport('useRecruitmentApi', () => () => api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

describe('ペナルティ設定の解除操作', () => {
  it('APIのactiveとliftedAtから状態と解除ボタンを表示する', async () => {
    const activePenalty = {
      id: 1,
      userId: 21,
      scopeType: 'TEAM',
      scopeId: 42,
      penaltyType: 'NO_SHOW',
      startedAt: '2026-11-20T12:00:00',
      expiresAt: '2026-12-20T12:00:00',
      active: true,
    } satisfies components['schemas']['RecruitmentUserPenaltyResponse']
    const penalties = [
      activePenalty,
      { ...activePenalty, id: 2, userId: 22, active: false },
      { ...activePenalty, id: 3, userId: 23, active: false, liftedAt: '2026-11-21T12:00:00' },
    ]
    api.getPenaltySetting.mockResolvedValue({
      data: {
        isEnabled: true,
        thresholdCount: 3,
        thresholdPeriodDays: 90,
        penaltyDurationDays: 30,
        autoNoShowDetection: false,
        disputeAllowedDays: 7,
      },
    })
    api.getScopePenalties.mockResolvedValue({ data: penalties, meta: { total: penalties.length } })

    const wrapper = await mountSuspended(PenaltySettingsPage, {
      global: {
        stubs: {
          PageHeader: true,
          SectionCard: true,
          Dialog: true,
          Button: {
            props: ['label'],
            template: '<button>{{ label }}</button>',
          },
          Tag: {
            props: ['value'],
            template: '<span data-testid="penalty-status">{{ value }}</span>',
          },
        },
      },
    })
    try {
      await flushPromises()
      expect(api.getScopePenalties).toHaveBeenCalledWith('TEAM', '42', 0, 20)
      const liftButtons = wrapper.findAll('button').filter(button =>
        button.text() === 'recruitment.penalty.liftButton',
      )
      expect(liftButtons).toHaveLength(1)
      expect(wrapper.findAll('[data-testid="penalty-status"]').map(tag => tag.text())).toEqual([
        'recruitment.penalty.status.active',
        'recruitment.penalty.status.expired',
        'recruitment.penalty.status.lifted',
      ])
    }
    finally {
      wrapper.unmount()
    }
  })
})
