# 横断課題正本（task-list.md）

## 位置づけ（二層構造）

Mannschaft の「タスクの地図」は二層で構成する。

- **戦役台帳**（`.claude/campaigns/*.md`）＝ 1戦役内の詳細地図（受け入れ条件・隊ごとの branch/worktree/PR進捗）。`.gitignore` 済みでローカル限定・**戦役が完全に完了したら削除**する揮発的なファイル。雛形は `.claude/campaigns/_TEMPLATE.md`。
- **`docs/task-list.md`（本ファイル）** ＝ 戦役をまたぐ横断の正本。git 追跡・永続。**行の粒度は戦役単位**（1戦役＝1行）。

戦役台帳が「今この瞬間の詳しい地図」だとすれば、本ファイルは「全戦役を見渡す目次」にあたる。**タスク単位（AC単位・隊単位）までは書かない**こと。それは戦役台帳の役割であり、ここに書くと二重管理になり必ずどちらかが陳腐化する。

### 更新タイミング

1. **戦役の開始時**（`/軍議` で御裁可が下りたタイミング）— 新しい行を追加する。
2. **`/検分`・マージ時** — 該当行の「状態」「証拠(PR/テスト)」を更新する。
3. **戦役が完全に完了したら** — 該当行を下部の「完了アーカイブ」節へ移動する（削除しない。横断の履歴として残す）。
4. **作業中に新しい問題を発見したら** — 既存行への追記でごまかさず、**必ず新しい行として登録する**。発見の経緯がわからなくなるため。

---

## 状態の語彙（戦役単位）

戦役台帳側の隊単位語彙（未着手/実装中/commit済/PR作成/CI緑/MERGED）とは別に、本ファイルは**戦役単位**で以下の語彙を使う。

| 状態 | 意味 |
|---|---|
| 未着手 | まだ着手していない |
| 設計中 | 軍議・設計書作成中。実装コードはまだ無い |
| 実装中 | コーディング中（PR未マージ含む） |
| 実機検証待ち | CI は緑だが、実機（ユーザー視点・モックなし）での動作確認がまだ済んでいない |
| 完了 | 実機検証まで済み、証拠（差分・テスト件数・実機ログ等）で裏取り済み |
| 凍結 | 意図的に一時停止（法務レビュー待ち等）。放棄ではない |

**「実装済み」と「実機検証待ち」を混同しないこと。** CI が緑であることは、コードがコンパイルでき単体/結合テストを通過した証拠にはなるが、実機で実際に動く証拠にはならない（本プロジェクトでは FE の CI は vitest を実行していない等、CI 緑と実質緑が乖離するケースが実際に存在する）。実機確認前の戦役を「完了」と書かないこと。

**完了は自己申告で判定しない。** 「終わったはず」という報告だけで「完了」に書き換えず、差分（PRのdiff）・テスト件数（シャードXMLなどの実測値）・実機ログ（スクリーンショット・実APIレスポンス等）のいずれかの証拠を「証拠(PR/テスト)」列に記録できて初めて「完了」とする。

---

## 戦役一覧

| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |
|----|------|------|------|---------|----------------|------|
| CMP-001 | 通知fan-out抜本改修（50万ユーザー目標） | 実装中 | — | 1万ユーザー規模で破綻（42.6秒/棄却9895件/N+1）した現行方式を、非同期＋バルク＋耐久キューへ再設計し50万ユーザー規模で許容時間内に完了すること | 50万本走実測(#2629): enqueue231ms・生成50万・DONE・完走43.9分(単一ワーカー190件/秒)。耐久土台は50万完走を実証、≤120秒はワーカー並列化(次Phase)へ。残タスク: ④50万負荷試験→**完了（単一ワーカー限界を実証）**／⑤ワーカー並列化（≤120秒達成・シャーディング） | `2026-07-30-fanout-redesign-p2.md` |
| CMP-002 | F17.2 村行事活性化 | 実装中 | — | Wave1（②寄合／④年輪）の実装・実機検証完了 | 設計 #2284（main済） | `2026-07-21-f17-2-village-events.md` |
| CMP-003 | 村FE/BE契約不一致17件戦役 | 設計中 | — | 高深刻度8件の軍議完了後、実装・実機検証まで完了 | 設計 #2284 | — |
| CMP-004 | WS外部ブローカー化＋IaCコスト削減戦役 | 未着手 | — | A案（Valkey中継）の実装・実機検証完了 | 御裁可済（未出陣） | — |
| CMP-005 | ADHDフレンドリーUX全面改修戦役 | 未着手 | — | 御裁可済の設計に沿った実装・実機検証完了 | 御裁可済（未出陣） | `2026-07-05-adhd-ux-overhaul.md` |
| CMP-006 | 予約v2根本再設計 | 実機検証待ち | — | D群4弾の実機E2E完了 | 設計 #2157(済)・D群4弾実装完了(#2525/#2536/#2539/#2553 全2026-07-30 main着地) | `2026-07-05-reservation-redesign.md` |
| CMP-007 | toBuilder更新破壊campaign | 実装中 | — | 第三波（約12件）の修正完了 | — | — |
| CMP-008 | 1000万ユーザーDB再構築 | 実装中 | — | 新規テーブルのUUID方針（ポストポリシー長PK違反ゼロ）を含む再構築完了 | — | — |
| CMP-009 | F18 残高凍結＋AccountPurge法務対応 | 凍結 | 法務レビュー | 法務レビュー完了後、AccountPurgeの実装・実機検証完了 | — | — |
| CMP-010 | 収益化・信頼の輪・ベータ特典の方針（F20.3） | 実装中 | — | ゲート③（規約弁護士確認）④（承認）⑤（UTC統一 #2486）完了 | ゲート①②完了（#2482） | — |
| CMP-011 | 月謝サブスクの開発（既存契約考慮不要） | 実装中 | — | 本番実契約が無い前提での実装・実機検証完了 | — | — |
| CMP-012 | 運用リスク3点の解消 | 未着手 | — | 本番前チェックリスト・ElastiCache HA ギャップ等の解消 | — | — |
| CMP-013 | 保留・未実装の小粒3件 | 未着手 | — | 郵便番号バリデーション基盤・組織配下チーム内訳等の実装完了 | — | — |
| CMP-014 | プロモ動画 制作資産（promo2） | 実装中 | — | promo2の8本すべての制作完了 | — | — |
| CMP-015 | 大名システムのプラグイン化 | 実装中 | — | daimyo-marketplace の git commit・main統合完了 | 抽出済（git未コミット） | — |
| CMP-016 | トークン週間リミット対策 | 実装中 | — | 殿モデル自動切替の実装完了 | CLAUDE.md ダイエット #2554（済・32.5→15KB）、MEMORY.md 退避（済） | — |
| CMP-017 | 認可漏れ(IDOR)全域監査戦役 残務7件 | 実装中 | — | 別チケット7件の処理完了 | Wave7 全14本 main 着地済（凍結795→653・穴119件処理済、2026-07-30）／第4波ロットA: chat・filesharing 29本を返済し凍結434→405（`ChatChannelAccessGuard` / `SharedFolderAccessGuard` を新設して認可判定を一元化、2026-08-05）／第7波 ロットA #2670（31件）・ロットB #2673（43件・戦役最終）で、並行着地した Wave6 ロットE #2666・ロットF #2667 との重複を解消のうえ返済完了。凍結ストアは 795（戦役開始）→434（第3波）→74（第6波）→**0（2026-08-09、全数返済）**、`EXPECTED_LINES_AUTHZ_WAVE4 = 0`。ロットBでは大会参加費チェックアウトについて、一覧取得と同一基準の対象判定を新設した | `2026-07-10-authz-idor-audit.md` |
| CMP-018 | F08.7 シフト予算連動機能（Phase 9-β/γ/δ） | 実装中 | Phase 9-α（完了） | Phase 9-β（DDL+CRUD#1-5）／9-γ（TODO紐付#7-8）／9-δ（警告/月次締め+権限）の実装完了 | Phase 9-α 実装完了（2026-05-03）: 逆算API #6単独・feature.shift-budget.enabled既定false | — |
| CMP-019 | TODO コメント棚卸し（FeatureFlag判定 34箇所ほか） | 未着手 | — | コード中の `// TODO` コメント（旧 `docs/TODO_LIST.txt` 記載分含む）を実チケット化し解消すること | — | — |
| CMP-020 | 本番のみで顕在化するスキーマ差の是正（照合順序不一致） | 実機検証待ち | — | 実装は完了・main着地済み。残るは**本番環境の構築後に V175 を適用し、当該APIの復旧を実機で確認すること**（本番は未デプロイのため現時点では実施不能） | PR #2591 マージ済（squash `65a72380a`、CI 全10チェック SUCCESS）。全migration走査で全726表を3分類（明示unicode_ci 551/明示0900_ai_ci 33/サーバ既定依存 142）し実害JOIN 1件を特定。V175統一migration＋`SchemaCollationConsistencyIT`(9件)＋`MigrationCollationDeclarationGuardTest`＋runbook。ローカル実測 37 tests/0 failures（#2589） | — |
| CMP-021 | 裏目付・第二陣の残務 | 実装中 | CMP-017 | #2541 の番人強化、#2544 の次の波、2スキーマ差分ITの導入判断、`my_scope_folder_items.scope_id` の符号性統一をすべて処理すること | 第二陣は 2026-07-30 完結（残=#2530）／**#2544（一度も効いていない`@Cacheable`横断監査）は PR #2568（第一波・2026-08-04マージ）・PR #2722（第二波・2026-08-11マージ、squash `0acc92d62`、CI 全10チェック SUCCESS）で完結し issue CLOSED。第二波はデッドコード2件撤去・`notificationStats`のevict欠落はTTL収束で妥当と判定（書込経路ゼロを実測）・`betaPerk:eligibility`のevict不在の設計意図を明文化・`dashboard:widget-visibility`のキー生成をString→ScopeType enumへ是正し正準一致を型で保証。番人テスト（`CacheableReturnValueShapeGuardTest`等）失敗0/エラー0・凍結ストア差分ゼロを実測。実機の観点は無し（キャッシュ内部リファクタのため）。→ CMP-021の残項目からは除外可**。CMP-021の他項目（#2541・2スキーマ差分IT・`my_scope_folder_items.scope_id`）は未着手のまま | — |
| CMP-022 | 番人テスト群の走査ロジック監査 | 完了 | — | migration SQL をテキスト走査する番人テスト群（第一波）に加え、Java ソースを走査する番人群（`//` `/* */` のコメント規約系、第二波）についても同型の走査欠陥を洗い出し是正すること | **第一波**（migration SQL 走査番人）PR #2671（2026-08-09 マージ、squash `e1b4df6cc`、CI 全10チェック SUCCESS）: 番人4クラスの前処理を `SqlTextScanningUtils`（新規）へ共通化し、ブロックコメント未除去・`CREATE TEMPORARY TABLE`誤帰属・テーブル本体範囲の伸長・`split(";")`誤分割・二重化エスケープ誤認による走査暴走の5欠陥を是正。検出力低下なしを実測（CrossDomainFK実効482件完全一致・PK番人allowlist外0件/全2件・collation番人対象2件/違反0件）。回帰テスト`SqlTextScanningUtilsTest`(9ケース)等、ローカル全緑（XML実測）。**第二波**（Javaソース走査番人）PR #2702（2026-08-11 マージ、squash `68e2013bd`、CI 全10チェック SUCCESS）: `Files.readString`/`Files.lines`/`Files.walk`使用14件を機械的に列挙しJavaソースをテキスト判定する8件に絞込。欠陥なし4件（`AuthzGateReturnValueGuardTest`等）に対し、`PagingTotalCountSizeGuardTest`/`ErrorCodeHttpStatusDeclarationGuardTest`/`SecurityConfigRules`の3件にテキストブロック未認識による同型の走査欠陥（奇数個の生クォートで後続の真の違反がマスクされ検出から消える偽陰性）をfixtureで実証し是正。`JavaSourceScanningUtils`を新設し共通化（判定ロジックは各番人固有のまま維持。SQL用`SqlTextScanningUtils`とは文字列リテラル規則の違いによりあえて非統合）。回帰テスト`JavaSourceScanningUtilsTest`(9ケース)新設。実測（JUnit XML）: `PagingTotalCountSizeGuardTest`1件・`ErrorCodeHttpStatusDeclarationGuardTest`4件・同`$GuardLogicItself`7件・`IntentionallyPublicMatcherGuardTest`2件・`AuthorizedByPathConfigMatcherGuardTest`2件・`JavaSourceScanningUtilsTest`9件、いずれも失敗0/エラー0/スキップ0。凍結リスト`pageimpl_total_count_size_freeze.txt`は是正前後で違反件数・stale判定が完全一致し検出力低下なし。以上で第一波・第二波とも完了し戦役は完結 | — |
| CMP-023 | 時刻設計の全域是正（瞬間はUTC・壁時計はTZ付き）＋テナントTZ導入 | 未着手 | #2700 | `LocalDateTime` の二義使用を解消し `TimeZoneConfig` の強制JSTを撤去した上で、チーム単位のタイムゾーンで業務ローカル時刻が解釈されること（Issue #2684 の締切判定2件を含む） | 設計 #2698・#2680（main済）。CMP-031（#2508 Phase 1〜3）完了により、その Phase 3 で見送った3項目（`LocalDateTime.now()`/`LocalDate.now()` 1681件・`GoogleCalendarService.DEFAULT_TIMEZONE`・`.slice(0,10)` 系）を後続として受ける | — |
| CMP-024 | **募集キャンセル料の決済API未実装（F03.4）— デプロイ前必須** | 未着手（**2026-08-10 実測発見**） | — | キャンセル料が実際に徴収され、失敗時のリトライが決済APIを実呼び出しすること | キャンセル料の**算出・記録は実装済**（`RecruitmentCancellationPolicyService` / `RecruitmentCancellationPolicyTierEntity` / `RecruitmentCancellationRecordEntity` / `CancellationFeeEstimateResponse`）だが、**徴収する経路が存在しない**。`RecruitmentPaymentRetryBatch.processRetry` は意図的なスタブ（`// TODO: F03.4 決済APIとの統合実装`）で、リトライ回数を増やして常に `false` を返すのみ。**当バッチは毎時走るため、記録は決済を一度も試みないまま約3時間で3回のリトライ枠を使い切り `FAILED` に落ちる**。設計書 `F03.11_recruitment_listing.md:1897` が「決済 API 仕様書を別途作成」と記すが `docs/features/F03.4*` は**存在しない**。⚠️ 月謝サブスクの決済（F20.1 `StripeBillingPaymentGateway`）は実装済で本件とは別系統（destination charge 側）。着手時は(1)F03.4 仕様書の作成（軍議）、(2)スタブ解消、(3)**リトライ枠を空消費した既存記録の扱いの決定**が要る | — |
| CMP-031 | 日時JSONの送信側だけTZ変換する非対称設計の是正（Issue #2508・Phase 1〜3） | 完了（2026-08-11） | — | 海外TZユーザーの日時入力が往復で壊れず、LocalDate が1日ずれないこと | Phase 1（クエリパラメータのTZ）・Phase 2（契約とピッカー）・Phase 3（表示側のTZ統一）をすべて main 着地。Phase 3 の実機E2E（P3-03: ブラウザTZ=LA でも TERM 契約終了日が 8/20 のままずれない）が **green**（2026-08-11）。P3-03 は Issue #2657（`payment_items.type` の ENUM に `TERM` が無く TERM 項目が作成不能）で長く skip されていたが、**PR #2674 で根治し、初めて実行された**。**後続は CMP-023「時刻設計の全域是正＋テナントTZ導入」に送る**（Phase 3 で意図的に見送った3項目: 引数なし `LocalDateTime.now()`/`LocalDate.now()` 1681件の意味論精査、`GoogleCalendarService.DEFAULT_TIMEZONE`、分類C の `.slice(0,10)` 系） | — |

台帳列はローカル限定（`.gitignore` 済み）のファイル名を指すため、clone 直後の環境や worktree には存在しない。`—` は「まだ台帳を作っていない」の意。戦役の再開時に台帳ファイルを作成したら、この列にファイル名を書き足すこと。

---

## 完了アーカイブ

戦役が「完了」（実機検証済み・証拠あり）になったら、ここへ行を移す。現時点ではまだ無し。

| ID | 戦役 | 完了日 | 証拠(PR/テスト) |
|----|------|--------|----------------|
