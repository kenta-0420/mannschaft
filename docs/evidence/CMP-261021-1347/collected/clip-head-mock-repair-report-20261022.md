# appearance spec の公式Nuxt import変換循環の修正（2026-10-22）

worktree: work/ranch-avatar-clips-finalize-20261021
受領HEAD: 57368a965b961c36c3d8c9278cfcd9a6ee3ebde0
変更対象: frontend/app/plugins/appearance.spec.ts のみ。製品・runtime・DB・browser・login・commit/push操作なし。

## 原因の一次source

frontend/node_modules/nuxt/dist/index.mjs:2014 の UnheadImportsPlugin（name=nuxt:head:unhead-imports、enforce=post）がImportDeclarationを解析し、@unhead/vueのuseHeadを#app/composables/headのimportに書き換える（2030〜2043）。.nuxt/imports.d.tsのmodule解決一致だけを調べた前回の確認では、この追加のimport変換を見落としていた。

specがstatic importした実物つもりの unheadUseHead もこの変換の対象になり、head mock→boundary.useHead→unheadUseHead（実際には同じmock）の循環になる。公式CI39626694339のstack overflowと一致。

## 最小修正

static useHead import1行を削除し、vi.importActual<typeof import('@unhead/vue')>('@unhead/vue')で実モジュールを取得する。Nuxtの対象はstatic ImportDeclarationであり、この動的取得は書き換えない。実UnheadのcreateHead/useHead/renderDOMHead/renderSSRHeadと実storeは維持。assertion、case数、skip条件、afterEachのglobal cleanupは一切変更しない。

## 実Nuxt変換でのred/green

outputs/clip-dark-background-real-nuxt-transform.config.ts は既存frontend/vitest.config.tsを解決し、そこから実際のnuxt:head:unhead-imports pluginを取得してpure runnerへ渡す。Nuxtの変換を手書きで再実装した試験ではない。Nuxt全runtimeのWindows entry解決障害を迂回するが、今回問題となった実pluginとmock境界の相互作用を保持する。

- clip-head-mock-real-nuxt-red-20261022.log: 変更前specでclient3すべて RangeError / mock:13↔callback:47。CIと同じ再帰を再現。
- clip-head-mock-real-nuxt-green-20261022.log: 同じ実Nuxt transformで修正後client3 passed、SSR5は別環境用skip。
- clip-head-mock-real-nuxt-ssr-green-20261022.log: 同じ実Nuxt transformでSSRを実行したログ。

## 製品の回帰assertionの強さ

修正後の最終specをそのまま使い、従来のclip-dark-background-vitest.config.tsとgit showで保存した修正前appearance plugin原本snapshotを実行。

- clip-head-mock-original-plugin-red-20261022.log: 元製品でclient3すべて --bg-color がcreamへ戻るassertion（68行）でred。importActual化で製品バグの検出力は落ちていない。
- clip-head-mock-client-green-20261022.log: 修正済productでclient3+既存store12=15passed / SSR5skip。
- clip-head-mock-ssr-green-20261022.log: SSR5passed / client3skip。
- clip-head-mock-eslint-20261022.log: spec ESLint exit0。

skip0とは扱わない。clientとSSRの異なる環境を別実行するmatrix partition。

## 実コマンド

cwd=上記worktree/frontend。Nodeは [local-home]/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe。

```powershell
$taskNode='[local-home]/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$env:TEMP=(Resolve-Path ../outputs/clip-dark-background-temp).Path
$env:TMP=$env:TEMP
& $taskNode node_modules/vitest/vitest.mjs run --config ../outputs/clip-dark-background-real-nuxt-transform.config.ts app/plugins/appearance.spec.ts
& $taskNode node_modules/vitest/vitest.mjs run --config ../outputs/clip-dark-background-vitest.config.ts
$env:APPEARANCE_TEST_SSR='true'
& $taskNode node_modules/vitest/vitest.mjs run --config ../outputs/clip-dark-background-real-nuxt-transform.config.ts app/plugins/appearance.spec.ts
& $taskNode node_modules/vitest/vitest.mjs run --config ../outputs/clip-dark-background-vitest.config.ts app/plugins/appearance.spec.ts
$env:APPEARANCE_TEST_SSR='false'
$env:APPEARANCE_TEST_BASELINE='true'
& $taskNode node_modules/vitest/vitest.mjs run --config ../outputs/clip-dark-background-vitest.config.ts app/plugins/appearance.spec.ts
& $taskNode node_modules/eslint/bin/eslint.js app/plugins/appearance.spec.ts
```

Linux inventoryの wsl.exe --list --quiet は Wsl/E_ACCESSDENIED で取得不能。共有Linux環境の所有権変更や環境導入はしていない。Windows公式full configの既知entry障害は再試行せず、今回の実Nuxt変換を直接使用して再帰red/greenを得た。公式Linux CIの最終合格はRoot統合後に確認が必要。

最終結果: 実Nuxt transform client3pass/SSR5skip、実Nuxt transform SSR5pass/client3skip。pure client3+store12=15pass/SSR5skip、pure SSR5pass/client3skip、元product snapshotは最終specの背景色assertionで3red。ESLint exit0、diffcheck exit0。specのみ変更でfreeze済み。
