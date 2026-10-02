import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// mockHour で dayjs.tz(..., 'Asia/Tokyo') を使うため、mount 前（本番の plugins/dayjs.ts が
// 走るより前）に utc/timezone プラグインを明示的に登録しておく。
dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * useTimedMessage のユニットテスト。
 *
 * <p>commit 0554b54d4（typecheck 全エラー根治）で rt() を捨てて
 * <code>(raw as { value: string }[]).map(m =&gt; m.value ?? '')</code> に
 * 置き換えたリグレッションを再発防止するためのテスト。</p>
 *
 * <p>Nuxt i18n の tm() は配列の各要素を compiled message AST として返すため、
 * 単純な .value プロパティアクセスでは undefined → 空文字に潰れる。
 * rt() で各要素を解決するのが正しい挙動。</p>
 *
 * テストケース一覧:
 *  TIMED-001: 早朝(5-9時) — earlyMorning メッセージが解決される
 *  TIMED-002: 午前(9-12時) — morning メッセージが解決される
 *  TIMED-003: 午後(12-17時) — afternoon メッセージが解決される
 *  TIMED-004: 夕方(17-21時) — evening メッセージが解決される
 *  TIMED-005: 夜間(21-24時) — night メッセージが解決される
 *  TIMED-006: 深夜(0-5時) — night メッセージが解決される
 *  TIMED-007: tm() が compiled AST を返す状況で空文字にならない（リグレッション再発防止）
 *  TIMED-008: tm() が空配列を返す場合は message が空のまま
 */

// ============================================================
// useI18n の Nuxt auto-import モック
// tm() は compiled-AST-like なオブジェクト配列を返し、
// rt() はその AST から実文字列を取り出す挙動を模倣する。
// commit 0554b54d4 のリグレッションを再現するため、
// .value プロパティを意図的に持たないオブジェクトを返す。
// ============================================================

type CompiledMessageMock = {
  __mock: true
  body: string
}

const periodMessages: Record<string, CompiledMessageMock[]> = {
  earlyMorning: [{ __mock: true, body: '清々しい朝ですね' }],
  morning: [{ __mock: true, body: '良い朝です' }],
  afternoon: [{ __mock: true, body: '午後も頑張りましょう' }],
  evening: [{ __mock: true, body: '夕暮れの時間です' }],
  night: [{ __mock: true, body: '夜は静かに過ごしましょう' }],
}

const tmMock = vi.fn((key: string): CompiledMessageMock[] => {
  const period = key.replace(/^timedMessage\./, '')
  return periodMessages[period] ?? []
})

const rtMock = vi.fn((msg: unknown): string => {
  if (typeof msg === 'object' && msg !== null && '__mock' in msg) {
    return (msg as CompiledMessageMock).body
  }
  return ''
})

// Nuxt の auto-import から提供される useI18n をモックする
mockNuxtImport('useI18n', () => () => ({ tm: tmMock, rt: rtMock }))

// useTimedMessage は mockNuxtImport より後で import する必要がある（mockNuxtImport の
// 仕組み上、対象 composable を import する前にモックを登録しなければならない）。
// vitest + @nuxt/test-utils が hoisting を担当するため動作上は問題ないが、
// ESLint の import/first ルールに引っかかるためここで明示的に無効化する。
// eslint-disable-next-line import/first
import { useTimedMessage } from '~/composables/useTimedMessage'

// ============================================================
// テスト用ホストコンポーネント
// useTimedMessage は onMounted で pick() するため、
// mountSuspended でコンポーネントを mount してフックを発火させる。
// ============================================================

const TimedMessageHost = defineComponent({
  setup() {
    const message = useTimedMessage()
    return () => h('div', { 'data-test': 'message' }, message.value)
  },
})

// ============================================================
// 時刻モック用ヘルパー
//
// useTimedMessage は dayjs().tz(userTimezone) で「ユーザーTZ（既定 Asia/Tokyo）の壁時計」を
// 判定するため、テストが固定すべきは「実行機のローカル壁時計」ではなく
// 「Asia/Tokyo での壁時計」に対応する絶対時刻（instant）である。
// `new Date(year, month, day, hour, ...)` は実行機のローカルTZで解釈されるため、
// CI（UTC）とローカル（JST）で異なる instant になり判定結果がずれていた
// （実測: UTC 実行機では 9 時間分ずれて別の period が選ばれる）。
// dayjs.tz(...) で明示的に Asia/Tokyo の壁時計として instant を組み立てることで、
// 実行機のTZに依存しない再現を得る。
// ============================================================

function mockHour(hour: number) {
  const fixed = dayjs.tz(`2026-05-18 ${String(hour).padStart(2, '0')}:00:00`, 'Asia/Tokyo').toDate()
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(fixed)
}

// ============================================================
// テスト本体
// ============================================================

describe('useTimedMessage', () => {
  beforeEach(() => {
    tmMock.mockClear()
    rtMock.mockClear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('TIMED-001: 早朝(5-9時) — earlyMorning メッセージが解決される', async () => {
    mockHour(6)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.earlyMorning')
    expect(wrapper.text()).toBe('清々しい朝ですね')
  })

  it('TIMED-002: 午前(9-12時) — morning メッセージが解決される', async () => {
    mockHour(10)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.morning')
    expect(wrapper.text()).toBe('良い朝です')
  })

  it('TIMED-003: 午後(12-17時) — afternoon メッセージが解決される', async () => {
    mockHour(14)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.afternoon')
    expect(wrapper.text()).toBe('午後も頑張りましょう')
  })

  it('TIMED-004: 夕方(17-21時) — evening メッセージが解決される', async () => {
    mockHour(18)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.evening')
    expect(wrapper.text()).toBe('夕暮れの時間です')
  })

  it('TIMED-005: 夜間(21-24時) — night メッセージが解決される', async () => {
    mockHour(22)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.night')
    expect(wrapper.text()).toBe('夜は静かに過ごしましょう')
  })

  it('TIMED-006: 深夜(0-5時) — night メッセージが解決される', async () => {
    mockHour(2)
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(tmMock).toHaveBeenCalledWith('timedMessage.night')
    expect(wrapper.text()).toBe('夜は静かに過ごしましょう')
  })

  it('TIMED-007: tm() が compiled AST を返す状況で空文字にならない（リグレッション再発防止）', async () => {
    mockHour(10)
    const wrapper = await mountSuspended(TimedMessageHost)
    // commit 0554b54d4 で発生したリグレッション:
    // (raw as { value: string }[]).map(m => m.value ?? '') では空文字になる。
    // rt() を経由していれば必ず非空の文字列が得られる。
    expect(wrapper.text()).not.toBe('')
    expect(rtMock).toHaveBeenCalled()
  })

  it('TIMED-008: tm() が空配列を返す場合は message が空のまま', async () => {
    mockHour(10)
    // morning だけ空配列にする
    tmMock.mockImplementationOnce(() => [])
    const wrapper = await mountSuspended(TimedMessageHost)
    expect(wrapper.text()).toBe('')
    // rt() は呼ばれないはず（配列が空なので pick 対象なし）
    expect(rtMock).not.toHaveBeenCalled()
  })
})
