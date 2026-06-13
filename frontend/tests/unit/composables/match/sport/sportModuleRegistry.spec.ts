import { describe, it, expect } from 'vitest'
import { effectScope } from 'vue'
import {
  resolveSportModule,
  isSupportedSport,
} from '~/composables/match/sport/sportModuleRegistry'
import {
  getSportCatalog,
  usesSoccerStyleSheet,
  SPORT_CATALOGS,
} from '~/composables/match/sport/sportEventCatalog'

/**
 * F08.10 6-②b 動的 import モジュール選択 UT（04 §G.16）。
 *
 * 観点:
 *   MOD-001: isSupportedSport は連続時間制 3 競技（SOCCER/FUTSAL/BASKETBALL）で true
 *   MOD-002: resolveSportModule が競技に応じた正しいモジュールを動的 import で返す
 *   MOD-003: sport=null / 未対応競技は SOCCER モジュールへフォールバック
 *   MOD-004: 各モジュールの createTimer が機能するタイマーを返す（状態機械が動く）
 *   CAT-001: カタログ駆動のプリセット並び（サッカー=得点起点 / バスケ=2P/3P/FT 起点）
 *   CAT-002: usesSoccerStyleSheet（サッカー/フットサル=共通シート・バスケ=専用）
 */

describe('sportModuleRegistry（動的 import 選択）', () => {
  it('MOD-001: isSupportedSport は連続時間制 3 競技で true', () => {
    expect(isSupportedSport('SOCCER')).toBe(true)
    expect(isSupportedSport('FUTSAL')).toBe(true)
    expect(isSupportedSport('BASKETBALL')).toBe(true)
    expect(isSupportedSport('VOLLEYBALL')).toBe(false) // 後続波（セット制）
    expect(isSupportedSport('SHOGI')).toBe(false) // 後続波（ターン制）
    expect(isSupportedSport(null)).toBe(false)
    expect(isSupportedSport(undefined)).toBe(false)
  })

  it('MOD-002: 競技に応じた正しいモジュールを返す', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const futsal = await resolveSportModule('FUTSAL')
    const basket = await resolveSportModule('BASKETBALL')
    expect(soccer?.sport).toBe('SOCCER')
    expect(futsal?.sport).toBe('FUTSAL')
    expect(basket?.sport).toBe('BASKETBALL')
    // 全モジュール連続時間制
    expect(soccer?.stateModel).toBe('CONTINUOUS_TIME')
    expect(basket?.stateModel).toBe('CONTINUOUS_TIME')
  })

  it('MOD-003: null / 未対応競技は SOCCER へフォールバック', async () => {
    expect((await resolveSportModule(null))?.sport).toBe('SOCCER')
    expect((await resolveSportModule('VOLLEYBALL'))?.sport).toBe('SOCCER')
  })

  it('MOD-004: createTimer が機能するタイマーを返す（サッカー前後半 / バスケ Q1）', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const basket = await resolveSportModule('BASKETBALL')
    const scope = effectScope()
    await scope.run(async () => {
      const st = soccer!.createTimer()
      expect(st.state.value).toBe('WAITING')
      await st.advance()
      expect(st.state.value).toBe('FIRST_HALF')

      const bt = basket!.createTimer()
      expect(bt.state.value).toBe('WAITING')
      await bt.advance()
      expect(bt.state.value).toBe('QUARTER_1')
    })
    scope.stop()
  })

  it('MOD-006: 各モジュールは eventSheet（遅延コンポ）を必ず提供する（live.vue の <component :is> 結線契約）', async () => {
    // live.vue は sportModule.eventSheet を <component :is> で描画するため、
    // 各競技モジュールが eventSheet を持つことが結線の前提（欠けると初期バンドル同梱の静的参照に逆戻り）。
    const soccer = await resolveSportModule('SOCCER')
    const futsal = await resolveSportModule('FUTSAL')
    const basket = await resolveSportModule('BASKETBALL')
    for (const mod of [soccer, futsal, basket]) {
      expect(mod?.eventSheet).toBeDefined()
      // defineAsyncComponent は object もしくは関数のコンポーネント定義を返す
      expect(['object', 'function']).toContain(typeof mod?.eventSheet)
    }
  })

  it('MOD-005: isNextCompleted が競技別に正しい（サッカー PK / バスケ Q4・OT）', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const basket = await resolveSportModule('BASKETBALL')
    expect(soccer!.isNextCompleted('PENALTY_SHOOTOUT')).toBe(true)
    expect(soccer!.isNextCompleted('QUARTER_4')).toBe(false) // サッカーに Q4 はない
    expect(basket!.isNextCompleted('QUARTER_4')).toBe(true)
    expect(basket!.isNextCompleted('OVERTIME')).toBe(true)
    expect(basket!.isNextCompleted('PENALTY_SHOOTOUT')).toBe(false) // バスケに PK はない
  })
})

describe('sportEventCatalog（カタログ駆動）', () => {
  it('CAT-001: プリセット並びが競技別（サッカー=goal起点 / バスケ=2P/3P/FT）', () => {
    const soccer = getSportCatalog('SOCCER')
    const basket = getSportCatalog('BASKETBALL')
    expect(soccer?.presets.map((p) => p.flow)).toEqual([
      'goal',
      'assist',
      'card',
      'substitution',
      'other',
    ])
    expect(basket?.presets.map((p) => p.flow)).toEqual([
      'basket_score',
      'basket_score',
      'basket_score',
      'rebound',
      'foul',
      'substitution',
      'other',
    ])
    // バスケ得点プリセットは 2P/3P/FT の event_type を持つ
    const scoreTypes = basket?.presets
      .filter((p) => p.flow === 'basket_score')
      .map((p) => p.scoreEventType)
    expect(scoreTypes).toEqual(['FIELD_GOAL_2', 'FIELD_GOAL_3', 'FREE_THROW'])
  })

  it('CAT-002: usesSoccerStyleSheet はサッカー/フットサル=true・バスケ=false', () => {
    expect(usesSoccerStyleSheet('SOCCER')).toBe(true)
    expect(usesSoccerStyleSheet('FUTSAL')).toBe(true)
    expect(usesSoccerStyleSheet('BASKETBALL')).toBe(false)
  })

  it('CAT-003: ポジション語彙が競技別（サッカー GK/DF/MF/FW・フットサル GK/FIXO/ALA/PIVO・バスケ PG..C）', () => {
    expect(SPORT_CATALOGS.SOCCER.positions).toEqual(['GK', 'DF', 'MF', 'FW'])
    expect(SPORT_CATALOGS.FUTSAL.positions).toEqual(['GK', 'FIXO', 'ALA', 'PIVO'])
    expect(SPORT_CATALOGS.BASKETBALL.positions).toEqual(['PG', 'SG', 'SF', 'PF', 'C'])
  })
})
