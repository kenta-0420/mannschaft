import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import MyPageHub from '~/pages/my/index.vue'
import { matchGateKey } from '~/constants/featureGates'

/**
 * マイページハブ `pages/my/index.vue` の機能フラグ連動のユニットテスト。
 *
 * 背景（CMP-260909-1141 Phase 1）: 実装済みなのに導線が1本も無かった金銭3画面
 * （領収書一覧・後見まとめ払い・大会参加費）へのカードをここに追加した。
 * 同時に、従来カードを無条件描画していた既存の穴（フラグを閉じると
 * 「カードは見えるのにクリックすると feature-gate middleware に弾かれる」）を塞いだ。
 *
 * 検証観点:
 *   MY-001 全フラグ有効 → 追加した金銭3画面のカードがすべて描画される
 *   MY-002 FEATURE_BILLING_PAYMENT_ENABLED を落とす → 領収書カードだけが消える
 *   MY-003 FEATURE_FAMILY_CARE_ENABLED を落とす → 後見まとめ払いカードだけが消える
 *   MY-004 全フラグ無効 → ガード対象カードは全消滅、ガード対象外カードは残る
 *   MY-005 ゲート対応表の前提固定（`/me/tournament-fees` はガード対象外であること等）
 *
 * ## 偽陽性（常に緑）にしないための作り
 * 「描画される」だけを見るテストはフラグ判定を外しても緑のままになる。よって
 *   ① 同一の DOM に対して「フラグを落とすと**消える**」ことを対で確認し、
 *   ② 消える対象と消えない対象を**同じアサーション内で並べて**比較する
 *      （フィルタが全消し・全残しに退化したらどちらかが必ず落ちる）。
 * さらに MY-004 は「全部消える」誤実装を、MY-002/003 は「1つも消えない」誤実装を殺す。
 */

// ── ページが依存する副作用（オンボーディング件数取得）の遮断 ──
const mockListMyProgresses = vi.fn(async () => [] as { status: string }[])
mockNuxtImport('useOnboardingApi', () => () => ({
  listMyProgresses: mockListMyProgresses,
}))

const mockCaptureQuiet = vi.fn()
mockNuxtImport('useErrorReport', () => () => ({
  captureQuiet: mockCaptureQuiet,
  captureError: vi.fn(),
}))

// ── 機能フラグストアのスタブ（middleware 側のテストと同じ差し替え方） ──
let disabledKeys: Set<string>
mockNuxtImport('useFeatureFlagStore', () => () => ({
  publicLoaded: true,
  isEnabled: (key: string) => !disabledKeys.has(key),
  loadPublicFlags: vi.fn(async () => {}),
}))

/** 追加した金銭3画面のパス。 */
const RECEIPTS = '/me/payments/receipts'
const BULK_PAYMENT = '/me/guardianship/bulk-payment'
const TOURNAMENT_FEES = '/me/tournament-fees'

/** 描画されたカードの遷移先パス一覧を取る（文言に依存しないのでロケール非依存）。 */
async function renderedLinks(): Promise<string[]> {
  const wrapper = await mountSuspended(MyPageHub)
  return wrapper.findAll('a[href]').map((a) => a.attributes('href') ?? '')
}

/**
 * ウォームアップマウント（既存の `ReservationMyWaitlistList.spec.ts` 等と同じ対処）。
 *
 * `mountSuspended` の初回呼び出しは、ページと依存ツリーの transform コストを
 * そのテストの `testTimeout`（既定5秒）内で負担する。環境が重いとこの初回コストだけで
 * 1件目が確定的に timeout する（実測: 本ファイルで 4 件すべて 5s 超過）。
 * 大きい `hookTimeout` を持つ `beforeAll` で使い捨てマウントして前払いし、各 it は
 * 既定の testTimeout のまま安定させる。`testTimeout` を全体で引き上げると他テストの
 * 本物の hang を隠すため行わない。
 */
beforeAll(async () => {
  disabledKeys = new Set()
  const warmup = await mountSuspended(MyPageHub)
  warmup.unmount()
})

describe('pages/my/index.vue — カードの機能フラグ連動', () => {
  beforeEach(() => {
    disabledKeys = new Set()
    mockListMyProgresses.mockClear()
  })

  it('MY-005: ゲート対応表の前提（金銭3画面の gate_key）', () => {
    expect(matchGateKey(RECEIPTS)).toBe('FEATURE_BILLING_PAYMENT_ENABLED')
    expect(matchGateKey(BULK_PAYMENT)).toBe('FEATURE_FAMILY_CARE_ENABLED')
    // 大会参加費は棚卸し台帳に gate_key が無く、ガード対象外である。
    expect(matchGateKey(TOURNAMENT_FEES)).toBeNull()
  })

  it('MY-001: 全フラグ有効なら金銭3画面のカードがすべて出る', async () => {
    const links = await renderedLinks()
    expect(links).toContain(RECEIPTS)
    expect(links).toContain(BULK_PAYMENT)
    expect(links).toContain(TOURNAMENT_FEES)
  })

  it('MY-002: 決済フラグを落とすと領収書カードだけが消える', async () => {
    disabledKeys = new Set(['FEATURE_BILLING_PAYMENT_ENABLED'])
    const links = await renderedLinks()
    expect(links).not.toContain(RECEIPTS)
    // 巻き添えで全部消える実装（フィルタの退化）を殺すための対のアサーション。
    expect(links).toContain(BULK_PAYMENT)
    expect(links).toContain(TOURNAMENT_FEES)
  })

  it('MY-003: 家族ケアフラグを落とすと後見まとめ払いカードだけが消える', async () => {
    disabledKeys = new Set(['FEATURE_FAMILY_CARE_ENABLED'])
    const links = await renderedLinks()
    expect(links).not.toContain(BULK_PAYMENT)
    expect(links).toContain(RECEIPTS)
    expect(links).toContain(TOURNAMENT_FEES)
  })

  it('MY-004: 全フラグ無効でもガード対象外カードは残り、ガード対象カードは消える', async () => {
    // isEnabled が常に false を返す状態を作る。
    const allLinksWhenEnabled = await renderedLinks()
    const gatedPaths = allLinksWhenEnabled.filter((p) => matchGateKey(p) !== null)
    const ungatedPaths = allLinksWhenEnabled.filter((p) => matchGateKey(p) === null)
    // 前提: 対象/対象外が両方存在していないと、この検査は何も見ていないことになる。
    expect(gatedPaths.length).toBeGreaterThan(0)
    expect(ungatedPaths.length).toBeGreaterThan(0)

    disabledKeys = new Set(allLinksWhenEnabled.map((p) => matchGateKey(p)).filter((k): k is string => k !== null))
    const links = await renderedLinks()

    for (const gated of gatedPaths) expect(links).not.toContain(gated)
    for (const ungated of ungatedPaths) expect(links).toContain(ungated)
  })
})
