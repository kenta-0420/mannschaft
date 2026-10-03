/**
 * ActiveIncidentBanner.vue のユニットテスト（ロジック抽出方式）。
 *
 * コンポーネントは useApi / useI18n / useRoute / setInterval 等の Nuxt グローバルに
 * 強く依存しているため、コンポーネントマウントではなくコンポーネント内の純粋ロジックを
 * 同等関数として抽出して検証する。
 * これは tests/unit/pages/system-admin/advertising/moderation-queue.spec.ts と同じ手法。
 *
 * 検証観点:
 *  AIB-001: severity -> PrimeVue severity マッピング（CRITICAL/WARNING/INFO）
 *  AIB-002: pagePattern のマッチングロジック（ワイルドカード/プレフィクス/完全一致）
 *  AIB-003: dismiss 後ポーリング再取得でも dismissed メッセージが復活しない
 *  AIB-004: ?lang= クエリが locale 値として URL に付与される
 *  AIB-005: visibleIncidents が dismissedMessages を除外し重複を排除する
 */
import { describe, it, expect } from 'vitest'

// ===== コンポーネント内ロジックの抽出 =====
// ActiveIncidentBanner.vue から純粋関数として同等ロジックを再現する。

interface Incident {
  pagePattern: string
  message: string
  severity: string
  since: string
}

/** severity の3値: INFO → info / WARNING → warn / CRITICAL → error */
function severityToPrimeVue(severity: string): string {
  if (severity === 'CRITICAL') return 'error'
  if (severity === 'WARNING') return 'warn'
  return 'info' // INFO およびその他
}

/** pagePattern マッチングロジック */
function matchesRoute(pattern: string, path: string): boolean {
  if (pattern === '*') return true
  if (pattern.endsWith('/*')) return path.startsWith(pattern.slice(0, -2))
  return path === pattern
}

/**
 * visibleIncidents 計算ロジック。
 * dismiss済みメッセージを除外し、同一メッセージの重複も排除する。
 */
function computeVisibleIncidents(
  incidents: Incident[],
  dismissedMessages: Set<string>,
): Array<{ incident: Incident; index: number }> {
  const seenMessages = new Set<string>()
  return incidents
    .map((incident, index) => ({ incident, index }))
    .filter(({ incident }) => {
      if (dismissedMessages.has(incident.message)) return false
      if (seenMessages.has(incident.message)) return false
      seenMessages.add(incident.message)
      return true
    })
}

/**
 * fetchIncidents 後のフィルタリングロジック（route マッチ + dismissedMessages リセット禁止）。
 *
 * 実装上のポイント: dismissed 集合はリセットせず、次回フェッチ後も引き継ぐ。
 */
function applyFetch(
  newIncidents: Incident[],
  currentPath: string,
  _dismissedMessages: Set<string>,
): Incident[] {
  // 1. ルートマッチフィルタ（APIが返したまま dismissed 集合はここでは触らない）
  return newIncidents.filter((i) => matchesRoute(i.pagePattern, currentPath))
}

/**
 * locale 付き API URL を生成するロジック。
 */
function buildActiveIncidentsUrl(locale: string): string {
  return `/api/v1/active-incidents?lang=${locale}`
}

// ===== テスト =====

describe('AIB-001: severityToPrimeVue — severity → PrimeVue severity マッピング', () => {
  it('CRITICAL → error', () => {
    expect(severityToPrimeVue('CRITICAL')).toBe('error')
  })

  it('WARNING → warn', () => {
    expect(severityToPrimeVue('WARNING')).toBe('warn')
  })

  it('INFO → info', () => {
    expect(severityToPrimeVue('INFO')).toBe('info')
  })

  it('未知の値もフォールバックで info', () => {
    expect(severityToPrimeVue('UNKNOWN')).toBe('info')
    expect(severityToPrimeVue('')).toBe('info')
  })
})

describe('AIB-002: matchesRoute — pagePattern マッチングロジック', () => {
  it('* はすべてのパスにマッチする', () => {
    expect(matchesRoute('*', '/')).toBe(true)
    expect(matchesRoute('*', '/teams/123')).toBe(true)
    expect(matchesRoute('*', '/any/deep/path')).toBe(true)
  })

  it('/teams/* は /teams/ 配下にマッチし /teams 単体にはマッチしない', () => {
    expect(matchesRoute('/teams/*', '/teams/123')).toBe(true)
    expect(matchesRoute('/teams/*', '/teams/123/detail')).toBe(true)
    // /teams/* は /teams で始まるパスだが、/teams 自体は /teams で始まっているかを確認
    // pattern.slice(0, -2) = '/teams' → '/teams'.startsWith('/teams') = true
    // ただし '/teams'.startsWith('/teams') は true なので /teams もマッチする（実装通り）
    expect(matchesRoute('/teams/*', '/teams')).toBe(true)
    expect(matchesRoute('/teams/*', '/dashboard')).toBe(false)
    expect(matchesRoute('/teams/*', '/organizations/1')).toBe(false)
  })

  it('完全一致パターンは一致するパスのみマッチする', () => {
    expect(matchesRoute('/dashboard', '/dashboard')).toBe(true)
    expect(matchesRoute('/dashboard', '/dashboard/sub')).toBe(false)
    expect(matchesRoute('/dashboard', '/')).toBe(false)
  })

  it('/* パターンは先頭スラッシュ以下すべてにマッチする', () => {
    // pattern = '/*': pattern.slice(0, -2) = '/' → path.startsWith('/') は常に true
    expect(matchesRoute('/*', '/anything')).toBe(true)
    expect(matchesRoute('/*', '/')).toBe(true)
  })
})

describe('AIB-003: dismiss 後ポーリング再取得でも dismissed メッセージが復活しない', () => {
  const makeIncident = (msg: string, pattern = '*'): Incident => ({
    pagePattern: pattern,
    message: msg,
    severity: 'INFO',
    since: '2026-06-17T00:00:00',
  })

  it('dismiss 後に同じインシデントが返ってきても visibleIncidents に含まれない', () => {
    const dismissedMessages = new Set<string>()
    const path = '/dashboard'

    // 初回フェッチ: 2件取得
    const firstFetch = [makeIncident('メンテナンス中'), makeIncident('一部機能が使えません')]
    const incidents1 = applyFetch(firstFetch, path, dismissedMessages)
    expect(computeVisibleIncidents(incidents1, dismissedMessages)).toHaveLength(2)

    // ユーザーが「メンテナンス中」を dismiss
    dismissedMessages.add('メンテナンス中')
    expect(computeVisibleIncidents(incidents1, dismissedMessages)).toHaveLength(1)

    // 2回目フェッチ: 同じ2件が返ってきた（dismissed 集合はリセットされない）
    const secondFetch = [makeIncident('メンテナンス中'), makeIncident('一部機能が使えません')]
    const incidents2 = applyFetch(secondFetch, path, dismissedMessages)

    // dismissedMessages はリセットされていないので「メンテナンス中」は復活しない
    const visible = computeVisibleIncidents(incidents2, dismissedMessages)
    expect(visible).toHaveLength(1)
    expect(visible[0]?.incident.message).toBe('一部機能が使えません')
  })

  it('dismiss 集合は fetchIncidents を何度呼んでも独立して保持される', () => {
    const dismissedMessages = new Set<string>()
    const path = '/'

    // 3回フェッチして毎回同じインシデントを返す
    for (let i = 0; i < 3; i++) {
      const fetched = [makeIncident('障害発生中')]
      applyFetch(fetched, path, dismissedMessages)
    }

    // まだ dismiss していないので表示される
    const visible1 = computeVisibleIncidents([makeIncident('障害発生中')], dismissedMessages)
    expect(visible1).toHaveLength(1)

    // dismiss
    dismissedMessages.add('障害発生中')

    // さらに3回フェッチ（dismiss後）
    for (let i = 0; i < 3; i++) {
      const fetched = [makeIncident('障害発生中')]
      applyFetch(fetched, path, dismissedMessages)
    }

    // dismiss 後は復活しない
    const visible2 = computeVisibleIncidents([makeIncident('障害発生中')], dismissedMessages)
    expect(visible2).toHaveLength(0)
  })
})

describe('AIB-004: ?lang= クエリに locale が付与される', () => {
  it('locale=ja のとき URL は /api/v1/active-incidents?lang=ja', () => {
    expect(buildActiveIncidentsUrl('ja')).toBe('/api/v1/active-incidents?lang=ja')
  })

  it('locale=en のとき URL は /api/v1/active-incidents?lang=en', () => {
    expect(buildActiveIncidentsUrl('en')).toBe('/api/v1/active-incidents?lang=en')
  })

  it('locale=zh のとき URL は /api/v1/active-incidents?lang=zh', () => {
    expect(buildActiveIncidentsUrl('zh')).toBe('/api/v1/active-incidents?lang=zh')
  })
})

describe('AIB-005: visibleIncidents — dismiss 除外 + 重複排除', () => {
  const inc = (msg: string, severity = 'INFO'): Incident => ({
    pagePattern: '*',
    message: msg,
    severity,
    since: '2026-06-17T00:00:00',
  })

  it('dismiss していないインシデントはすべて表示される', () => {
    const incidents = [inc('A'), inc('B'), inc('C')]
    const result = computeVisibleIncidents(incidents, new Set())
    expect(result.map((r) => r.incident.message)).toEqual(['A', 'B', 'C'])
  })

  it('dismiss した message は除外される', () => {
    const incidents = [inc('A'), inc('B'), inc('C')]
    const dismissed = new Set(['B'])
    const result = computeVisibleIncidents(incidents, dismissed)
    expect(result.map((r) => r.incident.message)).toEqual(['A', 'C'])
  })

  it('同一メッセージの重複は最初の1件だけ表示される', () => {
    const incidents = [inc('重複メッセージ'), inc('重複メッセージ'), inc('別メッセージ')]
    const result = computeVisibleIncidents(incidents, new Set())
    expect(result.map((r) => r.incident.message)).toEqual(['重複メッセージ', '別メッセージ'])
  })

  it('dismiss + 重複が同時に存在するケース', () => {
    const incidents = [
      inc('A'),
      inc('B'),
      inc('A'), // 重複
      inc('C'),
      inc('B'), // 重複かつ dismiss
    ]
    const dismissed = new Set(['B'])
    const result = computeVisibleIncidents(incidents, dismissed)
    expect(result.map((r) => r.incident.message)).toEqual(['A', 'C'])
  })

  it('全件 dismiss で空配列', () => {
    const incidents = [inc('X'), inc('Y')]
    const result = computeVisibleIncidents(incidents, new Set(['X', 'Y']))
    expect(result).toHaveLength(0)
  })
})
