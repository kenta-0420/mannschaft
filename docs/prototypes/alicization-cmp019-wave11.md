# CMP-019 Wave 11 実機・アリシゼーション（2026-09-27）

- 対象: Issue #3479 / PR #3481。TODO 一括変更時のロックスキップ応答と画面表示。
- 環境: 実 BE `localhost:8081`、実 FE `localhost:3003`、実 MySQL、Playwright Chromium。BE JAR は JDK 21 で作成し、実行には JDK 25 を使用した。

## 決めたシナリオの実機 E2E

`frontend/tests/e2e/real/cmp-019-bulk-todo-locked.real.spec.ts` の TEAM / ORGANIZATION 2 ケースが通過。各スコープに専用 project と milestone 2 件、TODO 4 件を作成し、実画面から一括変更を実行した。混在ロックでは変更 1 件・ロックスキップ 1 件、全件ロックでは変更 0 件・スキップ 2 件を API 応答、トースト、再読込後の選択、実 MySQL で照合した。非所属者からの対象 API は拒否された。代表画像は [TEAM 混在](evidence/cmp019-wave11/team-mixed.png)、[TEAM 全件ロック](evidence/cmp019-wave11/team-all-locked.png)、[ORG 混在](evidence/cmp019-wave11/organization-mixed.png)、[ORG 全件ロック](evidence/cmp019-wave11/organization-all-locked.png) に保存して実際に確認した。

初回 TEAM 試験は、共有管理者アカウントの「メンバーの権限を初期設定」ダイアログが一括操作を遮り、タイムアウトした。出現時に「あとで決める」で閉じる処理を加えて TEAM を再実行し通過。これはセッション内の表示状態のみを変え、DB の権限設定を変更しない。

決めたシナリオで作成した project 15〜17 と TODO は削除済みで、専用 TODO の active 0 件・milestone の実残存 0 件を DB で確認した。

## 3住民の自由探索

決めたシナリオとは別の `CMP019_W11_EXPLORE_1790461402446` を作成した。TEAM project 19 / TODO 533〜536、ORG project 20 / TODO 537〜540。各住民には人格と攻め口、達成目的だけを渡し、別 BrowserContext と別アカウントで探索させた。

| 住民 | 観測 |
| --- | --- |
| TEAM ADMIN（高齢・低リテラシー × 素直） | 実画面の TODO 一覧と初回権限設定ダイアログへ到達。[画面](evidence/cmp019-wave11/admin/team-list.png)で locked2・locked1・guard の行を確認。別の遷移は Loading が継続し、本人の一括操作は未達。console エラー・HTTP 4xx 以上は観測しなかった。fixture は未変更。 |
| 組織会員（注意散漫・中断からの復帰） | 独立 Context で組織 TODO 一覧へ到達し、fixture 4行が「未着手」、選択欄が有効であることを確認。[画面](evidence/cmp019-wave11/org-member/04-org-todos.png)には「2件のサーバーエラー」とエラー報告パネルが表示された。`GET /api/v1/organizations/org-000009/me/permissions` と `GET /api/v1/me/organizations` は HTTP 500。進捗変更と中断復帰は未実施、fixture は未変更。これらの 500 が本変更に起因する証拠は得ていない。 |
| 非会員（モバイル・権限境界を探る） | 専用アカウントと 390×844 のモバイル Context でログイン成功を確認。TEAM と組織の TODO URL はともに Loading が60秒以上継続し、対象一覧 API 呼び出しまで進まなかった。[TEAM 画面](evidence/cmp019-wave11/outsider/05-team-todos.png)・[組織画面](evidence/cmp019-wave11/outsider/05-org-todos.png)を確認。画面からの拒否・情報露出は判定できず、fixture は未変更。 |

## 後始末

探索 fixture は住民の観測終了後、[作成 ID の記録](evidence/cmp019-wave11/fixture.json)に限定して API で削除した。実 MySQL で TODO 533〜540 の active 0 件、milestone 10〜13 の実行 0 件、project 19/20 の active 0 件を確認した。共有 seed の他のデータは削除していない。
