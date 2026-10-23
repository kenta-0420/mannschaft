# CMP-261023-2217 個人ダッシュボード所属詳細プレビューの検証記録

- 対象: PR #4070 / `feature/dashboard-preview-20261023`
- 実装 head: `3f719ee207966efcc8ed922adba8316de2c7885f`。対象headのCIは全green。実装PR #4070はMERGED（2026-10-23T18:03:08Z）、mergeCommit `28d99fcd06d61810c198ead78f830512d536fb29`。origin/main先頭一致を確認。
- 目的: TEAM / ORGANIZATION の予定・掲示板・ブログ・チャット・TODOを、項目→実詳細モーダル→所属先ページの順で開く。カレンダーは認可済集計の概要から所属カレンダーへ進む。
- 新規 API・依存・DDLなし。BE変更はブログsummaryへのslug追加と既存test assertのみ。

## ACと実装・検証の対応

略記: Grid=`frontend/app/components/dashboard/DashboardSwipeWidgetGrid`、Modal=`frontend/app/components/dashboard/DashboardScopePreviewModal`、状態管理=`frontend/app/composables/useScopeWidgetPreview`。各unitは同名`.spec.ts`。

| AC | 実装 | greenの根拠と範囲 |
| --- | --- | --- |
| AC1 モーダルを先に開く | Gridと`DashboardScopeWidgetItem.vue`のnative button、Modal | Grid unitがTEAM/ORG各5種のclickと起点を確認。実機は両scopeの掲示板/TODOでキーボード→モーダル→CTAを確認。 |
| AC2 実詳細と空値 | 状態管理が5種の既存詳細APIを選択時に取得。Modalが本文/日時/期限等を表示 | 両scope・5種、空説明/期限なし、本文escapeのunit。実機では両scope掲示板/TODOの実GET200・固有本文を確認。 |
| AC3 所属/対象に合うCTA | id/scope/slug照合、既存`blogPostReadRoute`、予定`/schedule?eventId=` | unitが正規route・ブログ実parserを確認。実機で掲示板/TODOの両scopeリンクと遷移先本文。予定全件`/schedule`はGrid unit。 |
| AC4 カレンダー空/0件 | 認可済summary、追加GETなし、nullで架空項目なし | Grid/状態管理unitで0件・次予定なし・nullを確認。 |
| AC5 失敗/誤応答 | LOADING/ERROR/UNAVAILABLE、応答照合、FULLのみ本文/CTA | unitで403/404・retry・不正/未解決ID・別ID/別scope/複数所属を確認。実機で非所属TEAM/ORGの掲示板/TODO拒否4件。 |
| AC6 旧結果破棄 | request世代、close/dispose、primitive scope watch | unitで旧成功/旧失敗、close/scope/dispose、内部ID復旧と現対象retryを確認。 |
| AC7 安全な描画 | 既存`sanitizeHtml`/`useMarkdownRenderer` | Modal unitはrenderer接続。`frontend/tests/e2e/f221-swipe-dashboard.spec.ts`の実Chromium2件でscript/onerror/javascript除去、副作用なし、本文/CTA、Markdown strongを確認。 |
| AC8 操作/390px/focus/6言語 | native button `min-h-11`、PrimeVue Dialog、after-hide focus、6言語 | unitが属性/44px class/focus/close。実機はdesktop両scope4導線・390px TODO両scope2導線。探索360/390pxでoverflow0、新規操作44px。 |
| AC9 選択前取得なし/既存維持 | 選択時だけ詳細API、calendar GETなし、timeline/要対応を維持 | Grid/状態管理unitでcallなし/選択対象取得・既存2部品保持。探索で個人→所属切替と戻りを確認。 |
| AC10 CI/実機/探索 | unit、CI smoke、実機spec、3住民の探索 | 50unit・AC7ブラウザ2件・本実機1件・探索3名・最終CI全体は成功。実装PR #4070 merge済み。本完了記録で台帳と証拠JSONを対応付ける。 |

## 実行結果

- 限定同居3spec: 50件成功。ブログTEAM/ORGのmock内で実`parseBlogPostReadQuery`を通し、数値IDの実装で2件red→`String(numericId)`へ修正→50件green。privateログのwrapperは既存stderr警告により終了値1だったため、Vitest集計50件成功を根拠とする。
- AC7実Chromium: 2件成功、exit0、1worker/retry0/timeout120秒維持。掲示板約1.6分・ブログ43.7秒、計2.5分。navigation→hydration→row click→body assertionの全段階を通過。
- 本実機: `frontend/tests/e2e/real/dashboard-scope-preview.spec.ts`、1 passed / 6.2分 / exit0。非所属拒否4件、MEMBERのdesktop4導線、390px mobile2導線を通過。実GET/本文/モーダル中URL不変/close後focus/正規CTA/遷移先本文を確認。fixture cleanup成功。
- 探索: 下表の3名とも目的達成。general/interruptedのconsole errorは0、mobileは`scopeDashboard.loadTabs`の`RequestTimeoutError`が1件、pageerrorは0。実BE/実UIで行い、固定B0 runnerや20名coverageの実行とは扱わない。
- 探索fixture cleanup: 12操作すべて204/exit0。元所属件数へ完全復元（ADMIN TEAM278/ORG99、USER189/69、SUPPORTER3/1）。認証情報・生trace・private manifestは公開証跡へ含めない。
- 親の最終静的検分finding0。最終headのFrontend/Smoke/Backendほか対象CIは全green。

## 探索と未確定観測

原記録にpersona/journey IDがないため、登録時にCMP固有IDを付与。元run名はprivate evidenceのresidentディレクトリ名を保持する。

| 元run | persona/目的 | 結果・観測 |
| --- | --- | --- |
| resident-general | 一般×素直。所属の掲示板/TODOを読み元ページへ進む | 導線達成、console0。 |
| resident-interrupted | 中断傾向×再開。所属切替や中断後に本文へ戻る | 目的達成、console0。モーダルに所属名が明示されない点はnonblockingの未検証観測。 |
| resident-mobile | スマホ片手×隙間利用。360/390pxで本文とCTAを操作 | 目的達成、overflow0、新規操作44px。console error1件（scopeDashboard.loadTabs RequestTimeoutError）、pageerror0。既存chip38pxと既存scope-tabsの一時timeoutを観測。今回実装のbugとは断定しない。 |

mobileの一時timeoutは、3 browser contextsが存在するlarge DB環境で既存`GET /api/v1/dashboard/scope-tabs`を待った際の15秒RequestTimeoutErrorとして記録する。3 contexts/DB量と発生の因果、今回実装との関係は未確定。360pxモーダル画像は回復後の本文表示だけを示し、timeoutの発生証拠ではない。

気づき箱の構造化登録は`CMP-261023-2217-exploration.json`。3件すべてtrusted:false/status:observed-unverified/urgency:when-free。blockerへ追加せず、既存runner/coverage合格を意味しない。

## 安全な画像

`CMP-261023-2217/images/`へ目視確認済み7枚だけを無加工コピーした。TEAM/ORG掲示板desktopモーダル、TEAM TODOの390pxモーダルと遷移先、探索の360px ORGモーダル、390px所属chip、interruptedのORG TODOモーダル。テスト用fixture/E2E表示だけで、メール・Cookie・token・設定値・生通信を含まない。元private画像全体やtraceは複製していない。

- [TEAM掲示板モーダル](CMP-261023-2217/images/TEAM-bulletin-desktop-modal.png)
- [ORG掲示板モーダル](CMP-261023-2217/images/ORGANIZATION-bulletin-desktop-modal.png)
- [390px TODOモーダル](CMP-261023-2217/images/TEAM-todo-mobile-modal.png)
- [TODO遷移先](CMP-261023-2217/images/TEAM-todo-desktop.png)
- [360px ORGモーダル](CMP-261023-2217/images/explore-mobile-360-modal.png)
- [390px所属chip](CMP-261023-2217/images/explore-mobile-390.png)
- [所属名観測のORG TODOモーダル](CMP-261023-2217/images/explore-interrupted-org-todo-preview.png)

## 気づき箱overlay検証

`B0_RUNNER.md`のactual-resident-artifacts形式で3persona/3journey/3観測を宣言し、専用worktreeの`docs/prototypes/.b0-local/run-CMP-261023-2217.json`へ同一JSONを配置した。既存runner/dynamic runner/coverage試験は実行していない。IndexedDB/実気づき箱への取り込み済みという意味ではなく、次回の正規再生成へ渡せる登録データの準備である。

`B0_INCLUDE_LOCAL_OVERLAY=true`による正規生成はexit0。公式validator `--self-test-overlay`はoverlayへ到達する前のline46「GitHubスナップショットの参照集合が正本と不一致」でexit1。今回その既存同期不一致を変更していない。

正本の`validateRunOverlay`関数と自己検証ブロックを無変更でprivateスクリプトへ抽出し、宣言の受理・空insightsの受理・合成baselineの受理・不正8条件の拒否を確認（exit0）。これはoverlay単独の宣言整合確認であり、全board validatorの合格ではない。

生成前snapshotと検証後復元のboard-data SHA256はいずれも`B186CEB5859AFE42584823C06393CB793E1140D49C10CB5F17D38FF2B0A3685C`で一致。本担当は本陣のboard/HTML/既存overlayを変更していない。親が後で本陣`docs/prototypes/.b0-local/run-CMP-261023-2217.json`を新規配置し、公開overlayとSHA256 `6A5408D5EF6D849AACF777FB386D37FC4A8F6DDB273FEEC07FD82E148B90CAD9`一致を確認した。既存boardは未変更で、IndexedDB取り込み済みではない。

## サニタイズ試験の分担

happy-dom上の実DOMPurifyで危険HTMLが除去されないことを実測した。公式もhappy-domを非推奨としているため本体sanitizerを変更せず、unitを既存rendererへの接続、実除去負例をCI対象Chromiumへ分担した。期待を弱めていない。

一次資料: [DOMPurify: Running DOMPurify on the server](https://github.com/cure53/DOMPurify#running-dompurify-on-the-server)

## 最終headのCI進捗

親が同headの全CI greenと最終gate成功を確認。記録担当も実装PRのMERGED/head/mergeCommit/mergedAt、および以下4runの同一head/completed/successをGitHubから読み取り確認した。

| check | run | 確認結果 |
| --- | --- | --- |
| Frontend CI | [39983274426](https://github.com/kenta-0420/mannschaft/actions/runs/39983274426) | success。lint/typecheck成功、Vitest683files/6773passed/5skipped/失敗0。 |
| Smoke E2E | [39983274326](https://github.com/kenta-0420/mannschaft/actions/runs/39983274326) | success。 |
| Backend CI | [39983274376](https://github.com/kenta-0420/mannschaft/actions/runs/39983274376) | success。Compile & Test・全6shards成功。最後のshard3は1時間25分50秒。 |
| Harness CI | [39983274421](https://github.com/kenta-0420/mannschaft/actions/runs/39983274421) | 同一head、completed/success。 |

## マージと完了記録

PR #4070を2026-10-23T18:03:08Zにmerge。実装headは`3f719ee207966efcc8ed922adba8316de2c7885f`、mergeCommitは`28d99fcd06d61810c198ead78f830512d536fb29`。main追加4commitsは無関係のtests/docs/tasklistのみで所有重複0、複数回のmain追従実績・全CI green・MERGEABLEを親が確認し、凱旋規約の追いかけっこ例外としてadminを使用した。

本PRは完了JSON・台帳該当行・安全画像・検証記録・未確定観測overlayだけを記録するdocs-only変更。アプリ/runtimeは変更せず再実機を行わない。完了証拠CLIは構造・参照を検査し、GitHub API上のPR/CI照合は上記読取結果とCIゲートへ分けて扱う。

ローカル完了証拠CLIは`--base origin/main --head <stage tree>`で起動したが、全既存証拠の逐次Git読み取りが長期化したため所有プロセスだけを停止した（未完了、終了値-1）。ローカル合格とは扱わない。構造・参照とGitHub実装PR/CI照合を行う本完了PRのTask-List PR Gate成功を最終判定とする。validatorの変更や検証の弱体化は行っていない。
