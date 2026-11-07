// @vitest-environment nuxt
// 取得済みstateだけを表示する境界。実API/素材承認/実ブラウザは未証明。
import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import StatusPanel from '~/components/ranch/RanchStatusPanel.vue'
import Shelves from '~/components/ranch/RanchShelves.vue'
import type { RanchState } from '~/types/ranch'

const state: RanchState = {
 featureStatus: 'AVAILABLE', rewardsStatus: 'PAUSED', deliveryPaused: true, shopAvailable: false,
 owner: { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE', balance: '9007199254740993', version: '0' },
 dinosaur: { id: '22222222-2222-4222-8222-222222222222', speciesKey: null, variantKey: null, habitat: null,
  stage: 'EGG', name: null, namedAt: null, xp: '0', nextStageXp: null, version: '0', speciesCatalogVersion: null,
  affinityBand: 'WARM', egg: { startedAt: '2026-10-05T00:00:00Z', readyAt: '2026-10-12T00:00:00Z', crackStage: 'INTACT', hatchReady: false, hatchedAt: null } },
 settings: { isVisible: true, viewMode: 'ROOM', renderStyle: 'PIXEL', motionMode: 'REDUCED', isSoundEnabled: false, soundVolume: 50, version: '0' },
 careBudget: { weekStartsOn: '2026-10-05', weekEndsAt: '2026-10-12T00:00:00Z', remainingXp: '100', weeklyCapXp: '100', awardedXp: '0', amountXp: '20', ruleVersion: '1' },
 weekBudget: { remaining: '100', personalRequiredCount: '25', personalCompletedCount: 0 }, policyVersion: '1', assignment: null,
 roomSlots: [{ slotKey: 'SHELF_1', version: '0', inventoryId: '33333333-3333-4333-8333-333333333333', decoration: { collectibleKey: 'SYNTHETIC', labelKey: 'unknown.label', assetKey: 'https://synthetic.invalid/unapproved.svg' } },
  { slotKey: 'SHELF_2', version: '0', inventoryId: null }, { slotKey: 'SHELF_3', version: '0', inventoryId: null }],
 serverTime: '2026-10-05T00:00:00Z',
}

describe('ようすと3棚の表示境界（未実測）', () => {
 it('BIGINT文字列を保ちcare・point停止・配送pauseを独立表示し必要件数を保証へ変換しない', async () => {
  const wrapper = await mountSuspended(StatusPanel, { props: { state } })
  const t = useNuxtApp().$i18n.t
  expect(wrapper.text()).toContain('9007199254740993')
  expect(wrapper.text()).toContain(t('ranch.status.careAvailable'))
  expect(wrapper.text()).toContain(t('ranch.status.reward.PAUSED'))
  expect(wrapper.text()).toContain(t('ranch.status.deliveryPaused'))
  expect(wrapper.text()).toContain(t('ranch.status.referenceNotice'))
  expect(wrapper.text()).toContain(t('ranch.affinity.WARM'))
  expect(wrapper.find('progress').exists()).toBe(false)
  wrapper.unmount()
 })
 it('未参加nullを残高0や初期個体へ変換しない', async () => {
  const wrapper = await mountSuspended(StatusPanel, { props: { state: { ...state, owner: null, dinosaur: null } } })
  expect(wrapper.find('section').exists()).toBe(false)
  wrapper.unmount()
 })
 it('固定3棚を表示し未知assetの自由URLを画像として読み込まない', async () => {
  const wrapper = await mountSuspended(Shelves, { props: { slots: state.roomSlots, active: true } })
  expect(wrapper.findAll('section > div')).toHaveLength(3)
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.decorations.placed'))
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.html()).not.toContain('https://synthetic.invalid')
  await wrapper.setProps({ active: false })
  expect(wrapper.find('img').exists()).toBe(false)
  wrapper.unmount()
 })
})
