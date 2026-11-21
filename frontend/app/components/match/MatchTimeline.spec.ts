// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import MatchTimeline from './MatchTimeline.vue'
import MatchSpectatorView from './MatchSpectatorView.vue'
import type { MatchEventResponse, SpectatorConnectionState } from '~/types/match'

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/composables/match/useMatchLiveSpectator', () => ({
  useMatchLiveSpectator: () => ({
    snapshot: ref({ homeScore: 1, awayScore: 0, events }),
    connectionState: ref<SpectatorConnectionState>('LIVE'), hasSnapshot: ref(true),
    start: vi.fn(), stop: vi.fn(), resync: vi.fn(),
  }),
}))

const events: MatchEventResponse[] = [
  { id: 'home', eventType: 'GOAL', teamSide: 'HOME', minute: 12, playerName: '自選手' },
  { id: 'away', eventType: 'YELLOW_CARD', teamSide: 'AWAY', minute: 15, playerName: '相手選手' },
]

describe('観戦タイムラインの閲覧専用表示', () => {
  it('観戦ビューは両チームの記録を保ち、編集/削除ボタンを描画しない', () => {
    const wrapper = mount(MatchSpectatorView, {
      props: { orgId: 1, teamId: 42, matchId: 'match', ownTeamSide: 'HOME', opponentName: '相手' },
      global: { components: { MatchTimeline }, stubs: { MatchConnectionIndicator: true, MatchSpectatorScoreboard: true } },
    })

    expect(wrapper.text()).toContain('自選手')
    expect(wrapper.text()).toContain('相手選手')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('記録画面の既定は選択と自チーム記録の削除を保持する', async () => {
    const wrapper = mount(MatchTimeline, { props: { events, ownTeamSide: 'HOME' } })
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(3)
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([[events[0]]])
    expect(wrapper.emitted('delete')).toEqual([[events[0]]])
    expect(wrapper.findAll('[aria-label="match.live.timeline.delete"]')).toHaveLength(1)
  })
})
