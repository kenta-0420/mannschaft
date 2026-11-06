# CMP-261023-0051 完了報告

scope app。実装[PR #4063](https://github.com/kenta-0420/mannschaft/pull/4063)は2026-10-23T01:16:24Zにmerged。PR head `b1cfd1f80997434e539ee5495f29b0a0fcd1ac4b`、merge commit `59c5e88fb8048b0579a0536676ccf1f93080c2e4`。root最終検分finding0・出荷可、実機・探索・最終CIを含む完了証拠を記録する。

タイムラインは従来、初回取得・投稿操作に伴う再取得・古い投稿のページングを行い、新着の定期自動取得はない。共通feed上部へ44px以上の更新ボタンと6言語ラベルを追加した。同scope/myFeedをcursorなしで再取得し、投稿・ピン・cursor・hasNextを置換する。URL/documentや投稿草稿を保ち、手動操作の並列だけを抑止して既存内部refreshを維持する。

実機で別途確認した本人投稿編集の404は、TEAM/ORGの権限lookupへ数値scopeIdを渡したことが原因。2行をscope.slugへ変更した。slug省略/null/空文字はHTTPせず編集入口を閉じ、本人・既存権限条件は維持する。PUBLIC/VILLAGEの経路は変更していない。

## 検証と受け入れ条件

[受け入れ条件と実テスト名](./CMP-261023-0051.json)に対応を記録。Feed20件/Edit21件は最終Frontend CIでもpass。PostEdit対象はローカルで実assert21件pass（既存13+追加8、exit0）を確認した。[対象結果](./observations/CMP-261023-0051/targeted-post-edit-summary.json)。Nuxt初期化失敗はassert未実行として区別し、専用ignored設定で初期化hookだけ300s・worker1として実行。assert待機/testTimeoutは不変。最終headの全unit CIは成功し、下記に確定件数を記録した。

|類型|確認|
|---|---|
|正常/新着|同scope4種・myFeed先頭再取得、実機で新着表示|
|空/0/null|空結果で再更新、ミュート0、TEAM/ORG slug省略/null/空でfailclosed|
|境界|widget limit維持、新cursorの次ページ、44px以上|
|途中失敗|feed取得失敗時の投稿とpin維持・通知・再試行。slug欠落時はHTTPせず編集入口を閉じる|
|認可|本人/権限条件、実機別テナント403・匿名401・MEMBER正例|
|性能/並行|既存単一ページAPI、更新連打・IntersectionObserver・追加取得の並列抑止|

## 回帰の赤と緑

[実機red](./observations/CMP-261023-0051/red-edit-permissions.log)は16.6s、feed200と新着表示後、本人投稿の編集権限取得expected200/actual404で明示assert失敗、cleanup成功。[実機green](./observations/CMP-261023-0051/green-final.log)は1pass、全体2.2分exit0。slug権限200、正負権限、URL/document/草稿保持を確認した。

実機・探索FEは19ce時点の履歴。daacまではEdit.spec mockの1行のみだったが、実装head b1cには監査修正とDOMPurify3.4.17依存更新が加わった。既存Vueの製品実装は不変でも依存が異なるため、旧実機結果の自動継承とはせず、後述の新deps実機を別途実施した。BEの既存証拠は2bd9ベースJARであり、実装FE headと同一headとは扱わない。[source identity](./observations/CMP-261023-0051/source-identity.json)を参照。

## 監査ゲート修正と依存更新

ユーザー承認後、[軍議とA1-A13](./observations/CMP-261023-0051/audit-gungi-plan.md)をrootが検分して着手した。npmはmetavuln参照を依存名へ集約し、各entryのseverityと再帰展開先全体の最大severityは必ずしも一致しない。直接advisoryの過小報告は拒否し、間接参照で到達する全high/criticalをentryの報告severityに関係なく検証する最小修正を行った。critical/未知highを拒否し、EXEMPTIONS表・packages/nodes/edges・期限・末端版/range・間接依存条件は不変更。metadata/exitは生のnpm countで照合し、深刻度を書き換えない。

[先行red](./observations/CMP-261023-0051/audit-red.log)は20pass/4fail exit1、[green](./observations/CMP-261023-0051/audit-green.log)は24pass/0fail exit0。既存18件は拒否意味を維持し、未知moderate消費者1件の期待理由だけ変更した。[AC対応](./observations/CMP-261023-0051/audit-ac-trace.md)、[結果要約](./observations/CMP-261023-0051/audit-results.json)。対象mjs ESLint/Prettier、lock Prettier、diffcheckは成功。security Markdownの既存整形不一致は全節整形へ広げていない。

[実audit](./observations/CMP-261023-0051/audit-live.log)はexit0、critical0/high16/moderate3、DOMPurify指摘は消失し、high末端は既存forge/bracesの2GHSAだけ。新たな例外・期限延長はない。[root検分](./observations/CMP-261023-0051/audit-root-review.md)は独立24件実走exit0/skip0・finding0で、表の字節同一も確認した。このPhase1ローカル結果とは別に、下記の最終CI成功を確認した。

DOMPurifyは既存range内の3.4.16→3.4.17へlockのversion/resolved/integrityだけを更新。実アプリsanitize関数と3.4.17配布物による独立jsdom7assert pass、DOMPurifyをmockする既存Markdown22件passを区別する。独立npm10.9.8 ciは1401packages・約5分・exit0だが、Node24.15.0で指定22.23.2との差によるengine警告があり、ignore-scriptsでpostinstall/Nuxt prepareは未実施だった。共有depsは書き換えていない。[DOM限定検証](./observations/CMP-261023-0051/dompurify-summary.json)。後続で厳格互換patchとNuxt prepareを実施し、新deps実機A13の成功を観測した。下記のreceiptで限定検証時と区別する。

## 新依存での実機A13

[新deps実機ログ](./observations/CMP-261023-0051/audit-recheck-real-run.log)はb1c/DOMPurify3.4.17で1pass、exit0、skip0、全体3.3分（test本体3.0分）。元spec・360s timeoutは変更していない。更新feed200、本人編集権限200/slug、別アカウント拒否、匿名拒否、MEMBER更新200/別セッション新着、草稿・画面状態保持、所有fixture後始末を確認した。[結果](./observations/CMP-261023-0051/audit-recheck-result.json)、[source identity](./observations/CMP-261023-0051/audit-recheck-sourceidentity.json)。Node24.15とCI指定22の差、BE旧base2bd9 JAR利用、DB全体復元は主張しない制限を維持する。

初回はcold画面コンパイルで360s timeout、匿名以降は未了で、context閉鎖/finallyも失敗し未合格。その失敗を成功に含めない。記録されたmarker/TEAM1468/post768だけをGETで所有・scope照合し、DELETE204→GET404で限定cleanupした。[cold cleanup](./observations/CMP-261023-0051/audit-recheck-cold-run-cleanup.json)。その後の温環境で同条件再実行が成功した。成功runは作成直後IDの投稿2件/招待/TEAM DELETE204assertとcontextcloseが成功し、所属チーム一覧のmarker残存0を確認した。成功runについて事後の削除前GET照合をしたとは主張しない。[cleanup確認](./observations/CMP-261023-0051/audit-recheck-cleanup-verification.json)。

[依存準備receipt](./observations/CMP-261023-0051/audit-recheck-nuxt-prepare-receipt.json)は独立depsへのDevTools厳格補正とNuxt prepare exit0を記録。[visual QA](./observations/CMP-261023-0051/audit-recheck-visual-qa.json)、[mobile画像](./observations/CMP-261023-0051/audit-recheck-mobile.png)、[desktop画像](./observations/CMP-261023-0051/audit-recheck-desktop.png)を実閲覧し、更新・草稿・新着表示を確認した。rootと公開準備担当も2画像を実閲覧。既存sticky headerの重なりはあるが更新操作の障害は観測していない。

[FE停止](./observations/CMP-261023-0051/audit-recheck-fe-stop-receipt.json)後に3001接続拒否と終了signalを確認。続いて[BE停止](./observations/CMP-261023-0051/audit-recheck-be-cleanup.json)では8081接続不可・所有PID不在・Java SIGTERM graceful完了（JPA/Hikari終了）を確認した。[BE source](./observations/CMP-261023-0051/audit-recheck-be-sourceidentity.json)、[起動時health200](./observations/CMP-261023-0051/audit-recheck-be-health-receipt.json)。今回の再実機用起動/停止では既存container・本陣8080/3000を操作していない。初回検証で既存MySQL/Valkeyを停止状態から起動した履歴とは区別する。

## 最終CIと出荷検分

[最終CI要約](./observations/CMP-261023-0051/final-ci-summary.json)・[ログ抜粋](./observations/CMP-261023-0051/final-ci-excerpt.txt)を記録。Frontend run39880246188は実装PR headでcompleted/success。Node22.23.2でnpm ci・監査・lint・typecheck・単体試験を完了した。main Vitestは680 files、6723pass/5skip（6728件）、別ranchは2 files/27pass、対象Feed20件/Edit21件pass。監査gate24件＋既存DevTools patch3件の合算27件pass。

Backend run39880246230は変更検出と集約処理のsuccess。backendChanged=falseでshard/lint/coverage/non-JST/ArchUnitは対象外skipであり、Backend試験実走greenとは扱わない。Harness39880246208も同headでsuccess。最終PR checksは16success/7skip/0failure/0pending。旧daacのCI39833031781は監査Install失敗で、前述の修正後の最終CIと区別する。

[root最終検分](./observations/CMP-261023-0051/final-review.md)はAC-1〜6、監査A1-A12、新deps実機A13と許可境界を照合しfinding0・出荷可。実装の通常mergeを確認した。

## 3住民の観測

|住民/目的|結果・証拠|
|---|---|
|TL-P1/TL-J1 一般利用者・連絡を読み更新|目的達成、GET200・草稿/URL維持。[report](./observations/CMP-261023-0051/general-report.md)|
|TL-P2/TL-J2 スマホ・タッチと連打|目的達成、390/360px、7要求200、disabled/spinner・草稿維持・横パンなし。[report](./observations/CMP-261023-0051/mobile-report.md)|
|TL-P3/TL-J3 独語・表示崩れ|目的達成、5幅150.15625×44px、Aktualisierenで200、jaへ復元PUT/GET200。[report](./observations/CMP-261023-0051/german-report.md)|

3住民は19ceで実施した記録を保持する。その後のVue製品コード・locale・レイアウト差分0をrootが確認し、DOM実物7assertと新deps実機/画像で差分影響を裏取りしたため、この戦役のexploratoryを合格と判断した。b1cで3住民を再実施したとは称さない。[最終判断](./observations/CMP-261023-0051/final-review.md)。個別の住民観測はtrusted=false/observed-unverifiedを維持する。新Refreshは翻訳・44px・操作正常。既存の日本語見出し（en/de）と投稿元24px/日時20pxは2件の候補で、操作障害を観測しておらずblockerへ昇格しない。125%条件はviewport近似で、実ブラウザzoomではない。一般住民は別セッション新着作成をしていないため、新着の追加そのものは実機試験で担保した。

rootがB0専用ボード生成と元validator selftestを実施し全成功、不正overlay8件を拒否。validatorの検査ロジックは変更せず、入力data.jsだけを専用生成物へ向け、GitHubの635件の参照を専用出力へ読み取り同期した後にpassした。本陣の生成data.jsを直接検査した結果ではない。専用気づき箱へ2件を取り込み表示し、本陣の既存dirtyボードデータは変更していない。[overlay](./observations/CMP-261023-0051/resident-overlay.json)、[取り込みreceipt](./observations/CMP-261023-0051/insight-box-receipt.json)、[表示画像](./observations/CMP-261023-0051/insight-box.png)、[検証要約](./observations/CMP-261023-0051/board-validation-summary.json)。

## 後片付けと公開範囲

以下のcleanupと停止receiptは19ce時点の実機・探索後の履歴。新depsの今回分は前節のaudit-recheck-* receiptに分けて記録した。

実機セットアップの初回は通常local profileで起動し、2026-10-23 02:20 JST頃に既存scheduledジョブが発火した。batchログ、テスト利用者24へのBATCH_COMPLETED/BATCH_FAILED通知、既存error report集約count更新を観測した（runtime担当記録）。その後は既存local,e2e profileへ切り替えて自動ジョブを停止し、EMAIL_SIMULATE=trueを設定した。repair/clean/手動DDLは実施していない。マーカーのない既存データを含むため、これらの記録を推測で削除していない。削除確認は今回作成した実機・探索fixturesに限り、DB全体が元通りであるとは主張しない。

独語設定を元のjaへ戻した。実機・探索用の所有fixture（投稿・招待・チーム）は削除後不在を確認。[cleanup receipt](./observations/CMP-261023-0051/cleanup-receipt.json)。担当サーバー3001/8081は安全停止し、所有PID不在・Java正常shutdownを確認、本陣8080/3000は操作していない。既存MySQL/Valkeyコンテナは今回の検証で停止状態から起動したが、停止・再作成・リセットはしていない。[runtime receipt](./observations/CMP-261023-0051/runtime-cleanup-result.json)。

公開セットは選定レポート・集約観測・必要5画像・ログ/receiptのみ。スマホ360px、独語狭幅、気づき箱、新deps mobile/desktopの5画像をworkerが実閲覧し、E2E用表示以外の認証情報や私的連絡内容を認めなかった。rawtrace・cookies・browser profile・credentials・全API/profile応答は含めない。実装の最終CI・root出荷判断・マージを確認済み。この文書PR自体の審査・CIは実装PRとは別に行う。
