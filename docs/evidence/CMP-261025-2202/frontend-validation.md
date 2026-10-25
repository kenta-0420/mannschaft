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

## 追加検証・起動確定

- 親の静的照合: FE/BEカタログはTEAM38件・ORG40件で完全一致。ラベル51キー×6言語の欠落0、一般Sidebarの全pathに対応（固定homeは設定対象外）。
- CI `151dafb2bd` の型エラーは `DashboardSwipeWidgetGrid.spec.ts` のコールバック引数に `string` を明示して `6b0a1d9b26` で修正。親確認では同SHAのlint/typecheckが成功、Vitestは記録時点で進行中。
- Windows実環境はNode `v24.15.0` / npm `11.12.1`。規定Node `22.23.2` / npm `10.9.8` との不一致を記録し、正式環境の検証はCIで確認する。
- 初回Nitro bundleは407,950ms、dev全体初期化は589,605msで完了。起動中CPU/読取カウンタは増加し、3秒CPU profileではoxc-parserのAST解析、acorn、picomatch等を観測した。停止・メモリ枯渇として扱わない。
- その後 `/login` はHTTP200（3.364秒）、静的 `/robots.txt` はHTTP200（0.150秒）を確認。ブラウザ用とSSR用APIベースはともに専用BE `http://localhost:8081` を明示した。診断inspectorは停止済み、新規WSLコピーは作成していない。
- 専用MySQL13316へのWindows接続を確認。seedはFlyway・BE health成功後に限定する。実機と住民探索は引き続き未実施である。

## 生成契約の同期

CI artifact `6b0a1d9b26` の `FeatureDisplayPolicyItem` は `featureKey/memberVisible/supporterVisible` の3項目requiredを確認した。OpenAPIから型を再生成し、FEの手書きinterfaceを生成schemaのtype aliasへ置き換える。生成ファイルは直接編集しない。

同artifactでGET/PUTの成功200応答が欠落している点を検出し、ControllerのOpenAPI宣言修正をBEへ差し戻した。修正後CI artifact `b145b660af` ではTEAM/ORGのGET/PUT全4operationについて、200応答・content・`data.features[]`・要素の3項目requiredを辿って確認し、同artifactからOpenAPIと生成型を再同期した。

再生成成功後、TypeScript compilerで生成schemaを参照するalias単体を検査し、`featureKey: string` と独立bool2項目がすべて必須であること、型エラー0を確認した。aliasファイルのESLintも成功。

## 実機で検出した初回cache表示の不具合

初回実機は新規scopeの既存「メンバーの権限を初期設定」Dialogが機能設定タブを遮ったため中断した。試験前提の不足であり本機能のredと区別する。Dialogの「あとで決める」を通常UIで選ぶ初回手順をspecへ追加した。中断時にfinallyが閉鎖済みcontextでcleanupできなかったTEAM fixture（numericId11）は、作成時のslug・名前を照合してDELETE204→GET404を確認した。

その補正後、TEAM/ORGとも設定画面の見出し・2列header・保存ボタンは表示されるが設定行0件で失敗した。policy.featuresの即時watchでコピーしたdraftを、後続のscope/user即時watchが空に戻し、共有cache成功時のrefreshがno-opのため復旧しない実装不具合だった。

`FeatureDisplaySettingsPanel.spec.ts` にrefreshがfeaturesを変更しないcache-hit回帰を追加し、実SFCをmountして行exists期待true/実falseのassertion redを確認（他7件成功）。scope/user切替時はcurrent policy.featuresからdraftを初期化する1行修正後、8件全成功。refresh mockも実cache-hit同様のno-opへ合わせた。

このred/greenはdevの.nuxtと競合しない一時Vitest+Vue構成で、NuxtのmockNuxtImportをstubGlobalへ、Vue auto-importをglobal供給へ読み替えた。同じ提出specと実SFCを使用したが正規Nuxt経路の代替合格にはせず、CIの正規実行成功を別途必要とする。一時config/spec/setupの自分の3ファイルは撤去済み。実機の再走と住民探索は継続中であり合格扱いにしない。

## 実機のロール前提の訂正

初期specは招待roleId=5をSUPPORTERとしていたが、既存InviteServiceのjoinはMEMBER所属を作るため、このactorも実際にはMEMBERだった。最初の組合せ（member=false/supporter=true）の表示失敗を一時的に復元不具合と解釈したが撤回し、機能不具合のred証拠には数えない。管理者PUT・管理者GET・対象actor GETはすべてHTTP200でinfo=false/trueを返していた。

fixtureを通常のSUPPORTER受入有効化・follow経路へ変更し、承認済みを確認する。さらに両scopeでADMIN/MEMBER/SUPPORTER/非所属の4actorについてme/permissionsの実roleNameを前提assertする。補正後TEAMでは4actor前提と実SUPPORTERのinfo表示を通過したが、調査用response listenerのjson読取が画面遷移と競合し未捕捉例外となった。これは試験観測コードの失敗として区別する。listenerを除去し、所有TEAM15・ORG14はslug・名前・numericIdを照合して各DELETE204→GET404で後始末した。listenerなしの2本の本走を継続中。

listenerなし本走ではTEAMが総600,000msでtimeoutした。4通り目の両ON保存まで実UI操作が進行し、途中の対象assertion失敗はないが完走ではない。最後のsnapshotはADMIN機能設定画面であり、timeoutのcleanup時にcontextが閉鎖されていた。ORGは同じ総予算での反復を避け中断した。所有TEAM16・ORG15を限定cleanup後、40回を超える画面遷移と大量switch操作を行う試験の総予算を1,200,000msに調整する。一方で個別actionを30秒・navigationを60秒に制限し、有限のphase完了ログを追加して停止箇所を識別する。総予算変更だけを合格証拠にはせず、両scopeとも再走完了待ちである。
