# FE検証記録（2026-10-26）

対象: FE実装 `52ad5a5bc9`、追加修正は `ScopeDashboard` の並べ替え。

- 対象8ファイルのVitest: 7ファイル59件成功。`BaseSidebar.spec.ts` の23件は `@nuxt/test-utils/runtime/entry.mjs` の `setupNuxt` beforeAllが120秒timeoutとなり未実行（全体exit 1）。合格扱いにしない。
- 対象: `useScopeFeatureVisibility`、`useDashboardWidgets.feature-display`、`useRoleAccess`、`FeatureDisplaySettingsPanel`、`BaseSidebar`、`ScopeDashboard`、`DashboardSwipeWidgetGrid`、`ScopeActionRequiredWidget`。
- 全量typecheckはNuxt生成まで到達したが、実機dev起動を優先して自分のプロセスを停止。型検証とNuxt統合はCIでの成功確認が必要。
- 修正対象2ファイルのESLint成功。

## 並べ替えのred / green

全体順序 `timeline, chat, blog, bulletin`、個人非表示 `timeline`、機能表示設定で非表示 `chat` とする。通常グリッドで `bulletin` を `blog` へドラッグした場合、全体配列へ渡すべきindexは `[3, 2]`。

修正前SFCから `reorder / widgetIndex / onDragStart / onDrop` の実関数を抽出し、TypeScriptで変換してNode VMで実行した。実際は `[2, 0]` となりassertionが失敗（exit 1）。修正後はキーから全体indexへ対応付け、通常グリッドと設定dialogの両方が `[3, 2]` で成功（exit 0）。これはハンドラの再現検証であり、実ブラウザのドラッグ検証ではない。

`ScopeDashboard.spec.ts` に同じ混在条件でDOMのdragstart/dropを発火する回帰を追加。変更後componentテストはNuxt/Vitest importが長時間継続したため停止し、CI検証を残す。

## 実機準備

既存の専用 `cmp261025-feature-mysql`（localhost:13316、tmpfs）と `cmp261025-feature-valkey`（localhost:16389）の所有名・ポートを確認して再起動。停止前のtmpfs内容は保持されない。BE起動時のFlyway/seedが必要。

Windows FEは検証worktreeから `NUXT_PUBLIC_API_BASE=http://localhost:8081 npm run dev -- --port 3001` で起動。Vite client/server生成まで到達、記録時点でHTTP応答はまだ未確認。ホスト計測は空きRAM約20GiB/63GiB、CPU全体18%。メモリ枯渇の証拠はない。

実機本試験は未実施。対象specは `tests/e2e/real/cmp-261025-2202-feature-display.real.spec.ts`。BE8081とFE3001の応答、専用DBのテストユーザーを実測してから開始する。
