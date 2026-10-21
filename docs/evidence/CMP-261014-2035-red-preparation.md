# CMP-261014-2035 試練設置

基点: c7cd45baf29f466224e023a339fd127b69aa13ae。設計正本のv5を適用する。プロダクション実装は未変更。

## ACと試験

| AC | 試験 | 区分 |
|---|---|---|
| P1–P5 | tests/unit/components/PageHeader.spec.ts | 新副題のredと互換性回帰を分離 |
| G1–G10 | tests/unit/helpers/pageHeaderSubtitleGuard.spec.ts | AST検体と実app走査 |
| B1/B2 | tests/unit/helpers/friendI18nContract.spec.ts | de全locale（JSON/TS）、許可はキー×完全一致の2件 |
| R1/R6/R7 | 同上 | BE抽出集合、6言語、CIの両イベントpaths |
| R2–R4 | tests/unit/composables/useErrorHandler.spec.ts | de優先と既存分岐の回帰 |
| K1 | app/composables/friendFeedForwardContract.spec.ts | 実装済みcomposableの現契約固定 |
| C3 | FriendFollowDialog.spec.ts / friendFollowLocale.spec.ts | 1文字ヒント、既存2文字/debounce、6言語分離 |
| P10 | tests/unit/pages/teams/friend-folders-header.spec.ts | 権限4状態、19/20件、作成操作 |
| P11/P12 | tests/unit/pages/admin/settings-header-actions.spec.ts | 操作移行、loading、個人scope |
| P13 v5 | tests/unit/pages/admin/member-permissions.spec.ts | 正常未編集PUT、到達可能な阻害6条件、saving |
| C1 | tests/unit/pages/organizations/advertiser-campaign-header.spec.ts | 組織戻り先、CSV、取得失敗 |
| P6/P7 | tests/unit/helpers/pageHeaderGeometry.spec.ts | 操作群だけ折返す陰性検体。ブラウザ行列は未実施 |
| P8/P9/C4 | 移行前スクショと数値アンカー | P8 8枚＋P9 9枚＋C4 1枚を採取・実見済み。数値台帳参照 |
| P14/E1–E7/R5 | 既存ページ・E2Eの拡張 | 未設置部分あり。FE/E2Eは試練規約の実装後順序例外の対象 |

## 移行前観測

[AST観測](CMP-261014-2035-pre-migration-ast.json)は9画面・8違反。friend-foldersの残数は自己終了PageHeader外なので番人の対象外。announcementsの対象はteams/[slug]/announcements.vue。

## 検証環境

- 編集・commitは割当Windows worktreeだけ。WSL /home/kenta/verify-friend2035は検証用コピー。
- 本陣依存のmanifestは内容不一致だったためjunctionを非再帰で解除。本陣node_modulesは実体890件を維持。
- Windows Node24/npm11の試行、tsconfig未生成の3suite/Tests0、停止した試行はred証拠へ算入しない。
- 正式環境は専用WSL /home/kenta/verify-friend2035。Node 22.23.2 / npm 10.9.8でnpm ci・Nuxt prepare成功。本陣依存へnpm操作なし。
- git archiveの基点SHAと製品入力20件のLF正規化SHA256がWindows/WSL間で一致。試験overlay15件も同方式で照合。

## 正式な赤試験

| 実行 | 実件数 | 成功 | assert失敗 | skip |
|---|---:|---:|---:|---:|
| node環境4 spec（最終） | 59 | 41 | 18 | 0 |
| Nuxt環境8 spec（初回正式） | 71 | 57 | 14 | 0 |
| 存在assert・C1項目補完後の3 spec再実行 | 21 | 13 | 8 | 0 |

最終状態の重複なし合計は130件（98成功・32失敗）。再実行21件を追加件数に算入しない。未実装契約の失敗を確認する赤試験であり、green完了ではない。K1の3件とP13の11件は既存契約のgreen固定。対象15試験ファイルのESLintはexit 0。全体typecheck/E2Eは未実行。

実行ディレクトリは専用コピーのfrontend、各コマンドだけ既存Node22 binをPATH先頭へ追加。共有Node切替なし。

```sh
npx vitest run --config .cmp2035-vitest-node.config.mjs --reporter=json --outputFile=/tmp/cmp2035-node-red-final.json
npx vitest run tests/unit/components/PageHeader.spec.ts tests/unit/composables/useErrorHandler.spec.ts app/composables/friendFeedForwardContract.spec.ts tests/unit/components/friends/FriendFollowDialog.spec.ts tests/unit/pages/admin/member-permissions.spec.ts tests/unit/pages/admin/settings-header-actions.spec.ts tests/unit/pages/teams/friend-folders-header.spec.ts tests/unit/pages/organizations/advertiser-campaign-header.spec.ts --maxWorkers=1 --hookTimeout=600000 --testTimeout=600000 --reporter=json --outputFile=/tmp/cmp2035-nuxt-red.json
npx vitest run tests/unit/components/friends/FriendFollowDialog.spec.ts tests/unit/pages/admin/settings-header-actions.spec.ts tests/unit/pages/organizations/advertiser-campaign-header.spec.ts --maxWorkers=1 --hookTimeout=600000 --testTimeout=600000 --reporter=json --outputFile=/tmp/cmp2035-nuxt-correction-red.json
```

node専用configは試験対象4件、environment=node、maxWorkers=1、tsconfigRawでES2022を指定した一時入力。原本は証跡領域vitest-node.config.mjs。失敗の生JSONは同領域node-red-final.json、nuxt-red.json、nuxt-correction-red.json。初回のBOM解析失敗、入力不足、Tests0は正式表へ算入していない。

## 移行前画像とアンカー

[数値台帳](CMP-261014-2035-baseline.json)に18画面のURL、h1・旧副題・戻る・操作のrectとlineHeight、基点・overlay hashを保存した。PNGと生JSONのWindows絶対ディレクトリは C:/Claude/mannschaft/.claude/worktrees/agent-friend-2035-20261021/.claude/cmp2035-verify/pre-final/。contact-sheet.pngは17 desktopの実見用一覧。各原寸画像も保持。すべて対象URL一致、pageerrorなし。固定時刻2026-10-21T03:00:00Z、同一API fixture、ja1280×800、C4のみde360×800、light、fonts.ready後採取。実BEの成功証拠ではない。

フォルダ旧残数はPageHeader外なので初回subtitles=[]。同じfixtureで当該1画面だけ補完し、h1/actionsのrect完全一致を確認した。文字列「残り20個作成可能（最大20個）」、rect x84/y236/w234/h20、lineHeight20px。補完PNG/manifestは証跡領域folders-subtitle-supplement/。P9の文字列同一・副題1行分20pxの比較元とする。最終17画面を取り直していない。

C4は取り下げ。長名は意図したtruncate（h1 scrollWidth466/clientWidth224）で、document/viewport幅360px、親幅224pxの内側にタイトル・タグ・操作を実見確認した。de文字サイズ拡大は扱わない。

P8は移行後も同じfixtureで比較する。P9のタイトル位置、操作right±2px、許容縦移動は設計v4の戻る占有＋gap＋副題行高に従い数値台帳から比較する。採取済みは旧画面であり移行後合格判定は未実施。ブラウザは専用Chromiumからlocalhost:3002へ到達。Windowsの単独HTTP取得はタイムアウトしたため成功扱いにしていない。撮影用3002サーバーは補完完了後に停止した。

## 後続へ

P6/P7の全ブラウザ行列、P14/E1–E7/R5の未設置分、移行後のP8/P9差分確認はgreen担当が継続する。続きを読む/転送取消のUI追加は禁止、K1はcomposable契約だけ。line/sns直書きi18nは別課題。今回のcommitは製品実装を含まない。
