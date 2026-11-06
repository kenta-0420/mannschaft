# CMP-261021-0649 住民観測の統合気づき箱登録

製品ソース `dd41b081219c1e8bb127b07f4086e2d104903a11` の独立住民3体の観測を記録した。製品の出荷・AC20合格・coverage合格を意味しない。登録元は `actual-resident-artifacts`、全3件とも `trusted: false` / `observed-unverified` を維持する。

- A（ADHD傾向×中断・放棄）: UI提出HTTP 201、入力保持と自予定への復帰は達成。相談状態は探索範囲で未発見。状態消失・導線不存在とは断定しない。
- B（一般×素直）: 普段の画面から18時の相談を発見できず、受諾・成立確認は未達。自予定への復帰は達成。
- D（高齢・低リテラシー×隙間狙い）: ホームの承認待ちカードから不存在の相談URLへ進み404。承認・旧新担当予定の確認は未達。S3/Xの越境観測は相談URL404だけを認可境界成立の根拠にしない。

各目的・原ID・元runId・sourceHead・結果は `resident-A-summary.json`、`resident-B-summary.json`、`resident-D-summary.json`。殿の裏取りは `root-adjudication.json` に分け、既存CMP-261009-0616 / PR #3741への帰属と追加修正未裁可を保持した。独立再現harnessのexit1/passed=falseも合格へ書き換えていない。

既存の累積正本 `../CMP-261015-1510/resident-overlay.json` の13 insight / 6 persona / 6 journeyを値ごと保持し、3件を追記して16 / 9 / 9とした。既存stableIDを維持するためトップrunIdも固定した。HTML既存仕様によりIndexedDBのrunIdはトップrunIdとなるため、元runId `CMP0649-R02-cmp0649-79445b4f6a17` は各detailとevidenceに明示した。

既存生成スクリプトで再生成後、既存HTMLをfile URLで隔離headlessコンテキストへ開いた。テスト開始時のみ同じ生成データから新3件を除いたbaselineを初期化時に選択し、通常syncで13件を取り込んだ。次の通常ロードでは加工なしの生成データを読み、通常syncで16件へ追加された。既存13 ID保全、新3 ID各1件、B4気づき箱UIで全3カードと出所を表示、reload後16件不変を確認した。`board-registration.png` はその画面を実画像として確認した証拠であり、製品住民の視覚確認証拠ではない。利用者の既存ブラウザは更新していない。

全体 `node docs/prototypes/validate-beta-inventory-board.mjs --self-test-overlay` はGitHubスナップショットの参照集合が現main正本と不一致で不合格。元HEAD生成データはCMP597件、現main正本は598件で既にstaleだった。元HEAD/currentとも同じ参照集合検査が失敗（snapshot各665件）。今回GitHub同期へ範囲を広げていない。既存overlay関数と既存自己検証節だけを実行した補助検証は、16件受入、空・合成・複数run/head受入、不正10入力拒否に成功した。これは全体合格の代替ではない。

coverage、task-list、HTML、製品ソースは変更していない。検証の自browserは閉鎖、子プロセスなし、自TMP削除、検証Nodeの/proc不在を確認済み。結果の構造化要約は `registration-verification.json`。
