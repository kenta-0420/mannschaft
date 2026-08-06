/**
 * Vitest グローバルセットアップ — 変換（transform）コストの事前ウォームアップ。
 *
 * ## 背景（#2609）
 *
 * `environment: 'nuxt'` のテストは各ファイルの `beforeAll` で `setupNuxt()` を呼ぶ。
 * `setupNuxt()` は Nuxt アプリのエントリを読み込み、その過程で **ルーター定義のために
 * `app/pages/**` 配下の全ページを `?macro=true` で変換する**（`definePageMeta` の
 * 抽出）。テストが実際に何をマウントするかとは無関係に発生する固定コストで、
 * 実測（Vite の transform 計測をフックして採取）では 1ファイル単独のコールド実行でも
 * 変換 460 件・約 122 秒に達し、`hookTimeout`(120s) を超えて setupNuxt が落ちる。
 *
 * この変換結果はメインプロセスの Vite サーバー（client 環境）にキャッシュされ全ワーカーで
 * 共有されるため、2 ファイル目以降は速い。つまり **起動直後に並走したファイル群だけが
 * 全額を負担して落ちる**。同じ理由で、各ファイルの初回 `mountSuspended` も未変換の
 * コンポーネントを引き当てて `testTimeout`(5s) を超過する。
 *
 * ## 対処
 *
 * ワーカー起動前（＝いかなるタイムアウト計測の枠にも入らない）globalSetup で、同じ
 * Vite サーバーに対して同じ変換を先に要求しておく。テスト実行時にはキャッシュヒット
 * するだけになる。総変換量は変わらず、コストの計上場所を正すだけであるため、
 * タイムアウト値を緩める「症状隠し」とは異なる。
 */
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'
import { readdirSync } from 'node:fs'
import type { TestProject } from 'vitest/node'

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** 指定拡張子のファイルを再帰収集する（外部依存を持ち込まない）。 */
function collectFiles(dir: string, suffix: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) collectFiles(full, suffix, acc)
    else if (entry.name.endsWith(suffix)) acc.push(full.replace(/\\/g, '/'))
  }
  return acc
}

/** frontend/ ルート起点の Vite URL に正規化する。 */
function toViteUrl(absolutePath: string): string {
  return absolutePath.slice(frontendRoot.replace(/\\/g, '/').length)
}

/**
 * ウォームアップの起点。
 *
 * 1. 全ページの `?macro=true` — setupNuxt() が必ず引く最大の固定コスト
 * 2. 全テストファイル — 各ファイルの初回 mountSuspended が引く依存グラフの起点
 */
function collectEntries(): string[] {
  const pages = collectFiles(join(frontendRoot, 'app', 'pages'), '.vue').map(
    (p) => `${toViteUrl(p)}?macro=true`,
  )
  const specs = collectFiles(join(frontendRoot, 'tests', 'unit'), '.spec.ts').map(
    toViteUrl,
  )
  // `app/components/**` 等の自動インポート対象まで一括で温める案も試したが、
  // ウォームアップが 123 秒に伸びる一方で失敗ファイルは 8 → 12 に悪化したため
  // 採用しない（実測: after2 = 8 failed / after3 = 12 failed）。
  return [...pages, ...specs]
}

/**
 * ウォームアップ対象にできる URL かを判定する。
 *
 * 外部化されたベア指定子・Node 組み込み（`node:module` 等）や、実体を持たない
 * プレースホルダは Vite が読み込めず ERR_LOAD_URL になるため除外する。
 */
function isWarmable(url: string): boolean {
  if (url.includes('__vite-optional-peer-dep')) return false
  return url.startsWith('/')
}

/** 同時変換数。Vite の変換自体は単一プロセスのため過剰並列は無意味。 */
const CONCURRENCY = 8

export async function setup(project: TestProject): Promise<void> {
  const environment = project.vite.environments?.client
  if (!environment) {
    throw new Error(
      '[vitest warmup] Vite の client 環境を取得できませんでした。' +
        'Vite / Vitest の更新で Environment API が変わった可能性があります。',
    )
  }

  const seen = new Set<string>()
  const queue = collectEntries()
  const started = Date.now()

  const workers = Array.from({ length: CONCURRENCY }, async () => {
    for (;;) {
      const url = queue.pop()
      if (url === undefined) return
      if (seen.has(url)) continue
      seen.add(url)

      const result = await environment.transformRequest(url)
      if (!result) continue

      // 変換済みモジュールの依存を辿り、テストが引く可能性のある範囲を広げる。
      const mod = await environment.moduleGraph.getModuleByUrl(url)
      for (const imported of mod?.importedModules ?? []) {
        const dep = imported.url
        if (!seen.has(dep) && isWarmable(dep)) queue.push(dep)
      }
    }
  })

  await Promise.all(workers)

  const elapsed = ((Date.now() - started) / 1000).toFixed(1)
  console.info(
    `[vitest warmup] ${seen.size} モジュールを事前変換しました (${elapsed}s)`,
  )
}
