# FE検証記録（2026-10-26）

## 現在の判定

最終head `609ee90bf65af64b17d95e9ee31d27953c5abdaa` の [Frontend CI](https://github.com/kenta-0420/mannschaft/actions/runs/40246540366) は成功。親も実ログを照合し、正式Node 22.23.2/npm 10.9.8でlint・型チェック・705ファイル6,941件成功（既存skip5件）、後続2ファイル27件成功を確認した。設定Panel8件、wire変換2件、TEAM直URLガード5件も通常Nuxt経路で成功した。以前のガードsuite初期化失敗は、共通setupとの二重unmount登録だけを除去し、5件のassertionを変えず解消した。

実機はsource `48d6fc7349` 固定で **TEAM/ORGの2件成功、失敗0・skip0・flaky0**（exit 0）。TEAM 888,962ms（14.8分）、ORG 786,269ms（13.1分）、runner全体1,694,546ms（28.2分）。実行時の一時結果は `.claude/runtime/cmp261025-feature-fe/real-e2e-wire-guard-fixed-20261026/results.json`（永続JSON保存と照合後に撤去済み）。

完走結果の最小コピーは [real-e2e-result.json](real-e2e-result.json) に保存した。最終headまでFE製品コード差分はなく、FEの後続差分は上記の試験初期化だけである。BEの表示設定に対する後続修正は日時型・API宣言に限り、実MySQLの追加assertionと既存38件を含む50件および正式CIで成功を確認した。その後、先行main #4122の交代承認修正を取り込み、最新全量CIで統合確認する。範囲と再検証判断は[宣言・日時型の検証記録](architecture-validation.md)を参照する。

両scopeで実ADMIN/MEMBER/SUPPORTER/非所属を確認し、要対応3区分のAPI count>=1と実UIの正baseline、独立2列の4組合せ保存/再読込、通常Sidebar/スワイプ掲示板の表示制御、要対応3区分OFF/ON復元、全OFF/管理者レンズ復帰、info直URLの既存許可、非管理者の管理設定UI非表示/URL離脱、最後の全ON復元を通常UIで検証した。設定PUTは全てswitchと保存buttonから送信した。

実機TEAM24（teams-1792953236787）/ORG21（organizations-1792954125834）はfinallyの限定削除が成功し、専用DBでdeleted_at設定済み、adminの再GETで双方404を確認。afterAllで全4browser contextを閉鎖した。住民探索用の別TEAM25/ORG22を準備して親へ引継ぎ済み。assertion不変のtest初期化補正`c6431d71d8`後に正式Frontend CIが成功。残る全量BE CIと探索の統合判定は別記録で確認する。

## 実行履歴（以下は各記録時点の結果）

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

総予算補正後の本走はTEAMが要対応ON復元のbutton表示で失敗し、ORGが所属チップ準備で失敗した。TEAMの失敗snapshotはスワイプ画面ではなく通常のscope詳細だった。既存ScopeTabBarは選択済みチップの再クリックで詳細へ遷移するため、helperの無条件clickが遷移競合を起こしていた。選択済みならclickせず、未選択時だけ通常clickし、最終dashboard URL・選択状態・gridをassertする。ORGはデータ到着前のcount判定を避けtabbarと最初のchip描画を待つ。これらは試験helperの補正であり、従前の部分通過をスワイプACの最終greenには数えない。

両scopeのfinally削除成功後、当該時間帯の所有active scope0を専用DBで確認した。再走には、要対応集計APIの3区分count>=1とOFF前の3区分実UI表示を追加し、元々0件のままOFF assertionだけ通ることを防ぐ。追加変更はspecのみでproduct差分はない。正式Nuxt CIでは親確認により703file/6,934test成功（既存skip5）、後続27test成功、cache-hit回帰も正式経路でgreenとなった。

positive baseline追加後は両scopeのAPI3count>=1が成功し、実snapshotにも各1件の3区分headerが存在したが、headerのaccessible name先頭に既存アイコンglyphが含まれるため`/^回覧板/`等のselectorが不一致だった。3箇所のpositive/hidden/restoredを同じ「区分label＋数値件数」のheader locatorへ修正し、fixture明細buttonと区別する。productへテスト都合の変更は加えない。従前のhidden0件通過は確定証拠から除外し、この同一locatorのpositive baselineから再検証する。

## 実機で検出したスワイプロール契約の不具合

helperとheader selector補正後、TEAM/ORGとも要対応3区分の正のbaselineを通過した。しかし初回member=false/supporter=trueの保存後、MEMBERの通常Sidebarは非表示となる一方、dashboard上の掲示板headingが1件残り、期待0で両scope失敗した（`real-e2e-positive-20261026`）。今回はdashboard URL・選択chip・gridを確認しており、従前のhelper遷移とは異なる。

専用fixtureで実GETを確認すると、TEAM/ORG×MEMBER/SUPPORTERの4応答は全てHTTP200、`viewer_role`は各`MEMBER`/`SUPPORTER`であり、`viewerRole`は存在しなかった。BE DTOのJsonPropertyと一致する。useDashboardApiが生応答をFEのcamel型として返し、新PanelがviewerRoleを参照するため、actualRole=undefinedからeligible=falseとなって表示設定を適用しない不具合と確定した。観測用の専用fixtureはfinally削除済み。API境界のrole変換とsnake_case実応答回帰のred/greenは別担当へ委譲し、PanelやBE wire契約は変更しない。修正後の実機再走待ち。

## 非所属の管理設定直URLの独立観測

本走context閉鎖後、所有TEAM21・ORG20を用いた通常ブラウザのpreflightで非所属roleName=nullを確認した。TEAMは初期HTTP200、30秒後もmodules URLに残り、保存button0・設定row0だった。scope/me-permissions/follow-statusは200、modules APIは403、feature-policy APIは未送信。30秒後の実画面はshell下が空白で拒否文言やtoastはなかった（初期数秒のtoastは未捕捉）。ORGはhomeへredirectし保存button0・設定row0だった。双方finally削除後GET404を確認した。既存TEAM guardがnull roleをtruthy条件で除外する点を親へ返し、試験期待の緩和やguard修正は判断前に行っていない。

親承認に従いTEAM modules子routeだけを修正した。roleResolved=true・roleLoadFailed=falseで権限解決に成功した非管理者をhomeへ戻す。実SFC回帰でresolved-nullのnavigate期待1回/実0回というred（他4件green）を確認し、修正後5件green。未解決からADMIN確定・既解決ADMIN・ロール取得失敗の保持、正常MEMBERの既存redirectも確認した。この検証も一時Vitest+Vueで提出specのNuxt mockをglobal stubに読み替えた局所実行であり、正式Nuxt greenは後続CIで必要。一時3fileは撤去済み。

修正直後はFE HMR後のHTTP待機でpreflight navigation60秒timeoutしたため、合格扱いにしなかった。FE全体/loginも一時10秒timeoutし、その後robots200/6.95秒・login200/1.12秒へ復帰、BE health200/0.248秒を確認した。サーバー再起動はせず、温まった同サーバーで再走したTEAM23（guard-1792953167806）はrole=nullからhomeへ遷移し保存button0/row0を確認、DELETE204→GET404で後始末した。

API role正規化commit `88dcdaf8df` と未commitのTEAM guard/回帰・実機helper補正を固定し、`real-e2e-wire-guard-fixed-20261026`で2scope本走を再開した。以後本走中のfrontend source/spec/一時configは編集しない。未commit分は最終まとめcommit対象であり、HEAD単体の結果とは区別する。

同一内容を本走中にcommit `48d6fc7349` として確定した（内容編集なし）。TEAMは14.8分でPASS、正baseline・4組合せ・要対応3区分OFF/復元・全OFF/管理者レンズ復帰・info直URL既存許可・非管理者設定UI非表示/URL離脱・最終全ON復元・finally cleanupまで完走した。ORGは記録時点で正baselineを通過して実行中であり、まだ2scope合格とは扱わない。

## 最終実機結果

source `48d6fc7349` の同一ソースでTEAM/ORG両件PASS（2 passed、28.2分）、unexpected/skipped/flakyはいずれも0。上記途中runの未実施/未完走は履歴として残し、現在判定は冒頭の確定結果とする。テスト前提の訂正は機能redには数えず、初回cache表示・スワイプロールwire不一致・TEAM resolved-null guardはそれぞれ実装修正と回帰red/greenの対象として区別した。最終CIと住民探索は親が継続管理する。

## 現在の引継ぎ状態

実機scopeは削除済み。探索用の別TEAM25（`teams-1792955080937`、表示名「表示設定住民探索-teams-1792955080937」）はM/S全ON。別ORG22（`organizations-1792955082319`、表示名「表示設定住民探索-organizations-1792955082319」）はinfo=M OFF/S ON・bulletin=M ON/S OFF・他ONを通常管理UIで保存し、GETで実測した。各scopeでADMIN/MEMBER/SUPPORTER/非所属の実ロールもassert済み。

TEAMへADMIN user3、ORGへMEMBER user12・SUPPORTER user5を割り当てる。ログイン値を出力せず既存specのEMAILS/PASSWORDを参照する。準備用の全4contextは閉鎖済みであり、以後同ユーザーへ追加ログインせず親の3名並行探索へ渡した。URL/数値ID/表示名/固定policy/正確なDELETE URLと204→GET404の後始末手順は、秘密値なしの`.claude/runtime/cmp261025-feature-fe/resident-receipt.json`に記録した。FE3001は住民探索のため保持し、runtime receiptは05:00+09再確認期限。結果文書は未commitで親へ引継ぎ、完了台帳の正式ゲートと最終CIは親が担う。
