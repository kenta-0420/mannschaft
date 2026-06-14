import { describe, it, expect } from 'vitest'
import { effectScope } from 'vue'
import {
  resolveSportModule,
  isSupportedSport,
  isContinuousModule,
  isSetBasedModule,
} from '~/composables/match/sport/sportModuleRegistry'
import {
  getSportCatalog,
  usesSoccerStyleSheet,
  SPORT_CATALOGS,
} from '~/composables/match/sport/sportEventCatalog'

/**
 * F08.10 6-②b / 6-③b 動的 import モジュール選択 UT（04 §G.16）。
 *
 * 観点:
 *   MOD-001: isSupportedSport は連続時間制 3 競技 + VOLLEYBALL（セット制）で true
 *   MOD-002: resolveSportModule が競技に応じた正しいモジュールを動的 import で返す
 *   MOD-003: sport=null / 未対応競技（SHOGI 等）は SOCCER モジュールへフォールバック
 *   MOD-004: 各連続時間制モジュールの createTimer が機能するタイマーを返す
 *   MOD-005: isNextCompleted が競技別に正しい（サッカー PK / バスケ Q4・OT）
 *   MOD-006: 各モジュールは eventSheet（遅延コンポ）を必ず提供する
 *   MOD-009: VOLLEYBALL モジュールは SET_BASED・createSetTracker を持ちタイマーを持たない
 *   CAT-001: カタログ駆動のプリセット並び（サッカー=得点起点 / バスケ=2P/3P/FT 起点）
 *   CAT-002: usesSoccerStyleSheet（サッカー/フットサル=共通シート・バスケ=専用）
 *   CAT-003: ポジション語彙が競技別
 *   CAT-004: VOLLEYBALL カタログは SET_BASED・positions OH/OP/MB/S/L
 */

describe('sportModuleRegistry（動的 import 選択）', () => {
  it('MOD-001: isSupportedSport は連続時間制 3 競技 + VOLLEYBALL で true', () => {
    expect(isSupportedSport('SOCCER')).toBe(true)
    expect(isSupportedSport('FUTSAL')).toBe(true)
    expect(isSupportedSport('BASKETBALL')).toBe(true)
    expect(isSupportedSport('VOLLEYBALL')).toBe(true) // 6-③b でセット制追加
    expect(isSupportedSport('SHOGI')).toBe(false) // 後続波（ターン制）は未対応
    expect(isSupportedSport(null)).toBe(false)
    expect(isSupportedSport(undefined)).toBe(false)
  })

  it('MOD-002: 競技に応じた正しいモジュールを返す', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const futsal = await resolveSportModule('FUTSAL')
    const basket = await resolveSportModule('BASKETBALL')
    const volley = await resolveSportModule('VOLLEYBALL')
    expect(soccer?.sport).toBe('SOCCER')
    expect(futsal?.sport).toBe('FUTSAL')
    expect(basket?.sport).toBe('BASKETBALL')
    expect(volley?.sport).toBe('VOLLEYBALL')
    // 連続時間制モジュールの stateModel 検証
    expect(soccer?.stateModel).toBe('CONTINUOUS_TIME')
    expect(basket?.stateModel).toBe('CONTINUOUS_TIME')
    // セット制モジュールの stateModel 検証
    expect(volley?.stateModel).toBe('SET_BASED')
  })

  it('MOD-003: null / 未対応競技（SHOGI）は SOCCER へフォールバック', async () => {
    expect((await resolveSportModule(null))?.sport).toBe('SOCCER')
    expect((await resolveSportModule('SHOGI'))?.sport).toBe('SOCCER')
  })

  it('MOD-004: createTimer が機能するタイマーを返す（サッカー前後半 / バスケ Q1）', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const basket = await resolveSportModule('BASKETBALL')
    const scope = effectScope()
    await scope.run(async () => {
      // 型ガードで CONTINUOUS_TIME を確認してから createTimer を呼ぶ
      expect(soccer && isContinuousModule(soccer)).toBe(true)
      expect(basket && isContinuousModule(basket)).toBe(true)
      if (!soccer || !isContinuousModule(soccer)) return
      if (!basket || !isContinuousModule(basket)) return

      const st = soccer.createTimer()
      expect(st.state.value).toBe('WAITING')
      await st.advance()
      expect(st.state.value).toBe('FIRST_HALF')

      const bt = basket.createTimer()
      expect(bt.state.value).toBe('WAITING')
      await bt.advance()
      expect(bt.state.value).toBe('QUARTER_1')
    })
    scope.stop()
  })

  it('MOD-005: isNextCompleted が競技別に正しい（サッカー PK / バスケ Q4・OT）', async () => {
    const soccer = await resolveSportModule('SOCCER')
    const basket = await resolveSportModule('BASKETBALL')
    // 型ガードで CONTINUOUS_TIME を確認してから isNextCompleted を呼ぶ
    if (!soccer || !isContinuousModule(soccer)) throw new Error('soccer should be CONTINUOUS_TIME')
    if (!basket || !isContinuousModule(basket)) throw new Error('basket should be CONTINUOUS_TIME')

    expect(soccer.isNextCompleted('PENALTY_SHOOTOUT')).toBe(true)
    expect(soccer.isNextCompleted('QUARTER_4')).toBe(false) // サッカーに Q4 はない
    expect(basket.isNextCompleted('QUARTER_4')).toBe(true)
    expect(basket.isNextCompleted('OVERTIME')).toBe(true)
    expect(basket.isNextCompleted('PENALTY_SHOOTOUT')).toBe(false) // バスケに PK はない
  })

  it('MOD-006: 各モジュールは eventSheet（遅延コンポ）を必ず提供する（live.vue の <component :is> 結線契約）', async () => {
    // live.vue は sportModule.eventSheet を <component :is> で描画するため、
    // 各競技モジュールが eventSheet を持つことが結線の前提（欠けると初期バンドル同梱の静的参照に逆戻り）。
    const soccer = await resolveSportModule('SOCCER')
    const futsal = await resolveSportModule('FUTSAL')
    const basket = await resolveSportModule('BASKETBALL')
    const volley = await resolveSportModule('VOLLEYBALL')
    for (const mod of [soccer, futsal, basket, volley]) {
      expect(mod?.eventSheet).toBeDefined()
      // defineAsyncComponent は object もしくは関数のコンポーネント定義を返す
      expect(['object', 'function']).toContain(typeof mod?.eventSheet)
    }
  })

  it('MOD-009: VOLLEYBALL は SET_BASED・createSetTracker を持ちタイマーを持たない', async () => {
    const volley = await resolveSportModule('VOLLEYBALL')
    expect(volley).not.toBeNull()
    expect(volley?.stateModel).toBe('SET_BASED')
    expect(isSetBasedModule(volley!)).toBe(true)
    expect(isContinuousModule(volley!)).toBe(false)
    const scope = effectScope()
    scope.run(() => {
      if (!isSetBasedModule(volley!)) return
      const tracker = volley.createSetTracker()
      expect(tracker).toBeDefined()
      expect(tracker.trackerState.value).toBe('WAITING')
      expect(tracker.bestOf).toBe(5)
    })
    scope.stop()
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
    expect(SPORT_CATALOGS.VOLLEYBALL.positions).toEqual(['OH', 'OP', 'MB', 'S', 'L'])
  })

  it('CAT-004: VOLLEYBALL カタログは SET_BASED・プリセット空（主動線はセット入力シート）', () => {
    const volley = getSportCatalog('VOLLEYBALL')
    expect(volley).toBeDefined()
    expect(volley?.stateModel).toBe('SET_BASED')
    expect(volley?.presets).toEqual([])
  })
})