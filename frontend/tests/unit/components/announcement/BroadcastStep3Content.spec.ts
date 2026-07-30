import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '~/stores/useAuthStore'
import BroadcastStep3Content from '~/components/announcement/BroadcastStep3Content.vue'
import type { WizardFormState } from '~/types/announcement_broadcast'

/**
 * F02.8 告知ウィザード BroadcastStep3Content.vue ユニットテスト（Issue #2508 FEオフセット明示）。
 *
 * 背景: BE の expiresAt（リクエスト直下・LocalDateTime）と SURVEY の closesAt
 * （AnnouncementContentRequest.closesAt・LocalDateTime）は、受信時オフセットを無視する
 * 非対称バグがある。以前は toLocalDateTimeString() で TZ オフセット無しの壁時計文字列を
 * 送っていたため、useDatetime().buildOffsetDateTimeStr へ差し替えた。
 * 一方 SCHEDULE の startAt/endAt は BE で既に OffsetDateTime のため対象外（回帰確認のみ）。
 *
 * 検証観点:
 *   BC3-001: expiresAt セット時、ユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を emit する
 *   BC3-002: SURVEY の closesAt セット時も同様にオフセット付き文字列を emit する
 *   BC3-003: 非JST（America/Los_Angeles）ユーザーでもそのTZのオフセットが付く
 *   BC3-004: SCHEDULE の startAt/endAt は従来どおり toISOString()（Z終端）のまま変化しない
 */

function baseModelValue(overrides: Partial<WizardFormState> = {}): WizardFormState {
  return {
    step: 3,
    targetRole: 'MEMBERS_AND_ABOVE',
    targetTeamIds: null,
    selectedChannel: 'BULLETIN_THREAD',
    templateId: null,
    priority: 'NORMAL',
    expiresAt: null,
    content: {},
    ...overrides,
  }
}

/** 指定タイムゾーンで非同期処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
async function withSystemTz<T>(tz: string, fn: () => Promise<T>): Promise<T> {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return await fn()
  } finally {
    process.env.TZ = original
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('BroadcastStep3Content.vue', () => {
  it('BC3-001: expiresAt セット時、ユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を emit する', async () => {
    await withSystemTz('Asia/Tokyo', async () => {
      const wrapper = await mountSuspended(BroadcastStep3Content, {
        props: {
          modelValue: baseModelValue(),
          scopeType: 'TEAM',
          scopeId: '1',
          broadcasting: false,
        },
      })
      const vm = wrapper.vm as unknown as { expiresAt: Date | null }
      vm.expiresAt = new Date(2026, 5, 10, 18, 0, 0) // 2026-06-10 18:00 JST
      await wrapper.vm.$nextTick()

      const emitted = wrapper.emitted('update:modelValue')
      expect(emitted).toBeTruthy()
      const last = emitted?.[emitted.length - 1]?.[0] as WizardFormState
      expect(last.expiresAt).toBe('2026-06-10T18:00:00+09:00')
    })
  })

  it('BC3-002: SURVEY の closesAt セット時もオフセット付き文字列を emit する', async () => {
    await withSystemTz('Asia/Tokyo', async () => {
      const wrapper = await mountSuspended(BroadcastStep3Content, {
        props: {
          modelValue: baseModelValue({ selectedChannel: 'SURVEY' }),
          scopeType: 'TEAM',
          scopeId: '1',
          broadcasting: false,
        },
      })
      const vm = wrapper.vm as unknown as { surveyClosesAt: Date | null }
      vm.surveyClosesAt = new Date(2026, 5, 20, 9, 0, 0) // 2026-06-20 09:00 JST
      await wrapper.vm.$nextTick()

      const emitted = wrapper.emitted('update:modelValue')
      const last = emitted?.[emitted.length - 1]?.[0] as WizardFormState
      const content = last.content as { closesAt?: string | null }
      expect(content.closesAt).toBe('2026-06-20T09:00:00+09:00')
    })
  })

  it('BC3-003: 非JST（America/Los_Angeles）ユーザーでも expiresAt にそのTZのオフセットが付く', async () => {
    await withSystemTz('America/Los_Angeles', async () => {
      useAuthStore().user = {
        id: 1,
        email: 'la-user@example.com',
        fullName: 'LA User',
        profileImageUrl: null,
        timezone: 'America/Los_Angeles',
      }
      const wrapper = await mountSuspended(BroadcastStep3Content, {
        props: {
          modelValue: baseModelValue(),
          scopeType: 'TEAM',
          scopeId: '1',
          broadcasting: false,
        },
      })
      const vm = wrapper.vm as unknown as { expiresAt: Date | null }
      vm.expiresAt = new Date(2026, 5, 10, 18, 0, 0) // 2026-06-10 18:00 PDT
      await wrapper.vm.$nextTick()

      const emitted = wrapper.emitted('update:modelValue')
      const last = emitted?.[emitted.length - 1]?.[0] as WizardFormState
      expect(last.expiresAt).toBe('2026-06-10T18:00:00-07:00')
    })
  })

  it('BC3-004: SCHEDULE の startAt/endAt は従来どおり toISOString()（Z終端）のまま（OffsetDateTime のため対象外）', async () => {
    const wrapper = await mountSuspended(BroadcastStep3Content, {
      props: {
        modelValue: baseModelValue({ selectedChannel: 'SCHEDULE' }),
        scopeType: 'TEAM',
        scopeId: '1',
        broadcasting: false,
      },
    })
    const vm = wrapper.vm as unknown as { scheduleStartAt: Date | null }
    const d = new Date(2026, 5, 10, 18, 0, 0)
    vm.scheduleStartAt = d
    await wrapper.vm.$nextTick()

    const emitted = wrapper.emitted('update:modelValue')
    const last = emitted?.[emitted.length - 1]?.[0] as WizardFormState
    const content = last.content as { startAt?: string | null }
    expect(content.startAt).toBe(d.toISOString())
  })
})
