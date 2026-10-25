# 表示設定の検分記録

対象: PR #4116 / CMP-261025-2202。現在のCodex自身が検分し、別Codexの入れ子起動はしていない。

## 実装と受け入れ条件の照合

| AC | 確認した実装と検証対象 |
|---|---|
| 1 | 独立した2つのBoolean、TEAM/ORG別カタログ。ContractITの4組合せ保存・再取得とCommitITの初回並行保存。 |
| 2 | 未保存キーは既定true/true、GETでは行を作らない。ContractIT、ServiceTest、既存個人設定の保持テスト。 |
| 3 | Writer前に全入力を検証、空配列も認可後no-op。ContractITのnull/欠落/重複/未知/長さ/件数境界と全列不変。 |
| 4 | サイドバー、ホームタブ、通常grid、swipe、パネルに既存権限・モジュール条件とのANDを適用。対象外の固定ホームを除きサイドバーの一般導線を照合。 |
| 5 | 管理者通常表示はバイパス、MEMBERプレビューに設定を適用。ホーム、スコープ切替、管理表示への復帰を残す。実ブラウザでの確認は実機記録に分離する。 |
| 6 | Controllerで認証、Serviceで実所属ロール。GETはSUPPORTER以上、PUTはADMIN/SYSTEM_ADMIN。業務編集UIのADMIN限定はsecurity03の既存方針に従う。 |
| 7 | 生存slug解決をSYSTEM_ADMIN短絡より先に実施。他scope・親子組織の設定と所属を継承しない。ContractITの隔離・削除scope検証。 |
| 8 | 別ドメインの認可とmodule内書込TXを分離。CommitITで後続UPSERTの実DB例外による先行書込rollback、commit前監査0件、commit後記録を検証。 |
| 9 | 表示制御は業務データと個人hide/orderを書き換えない。既存行のID/created_at保持、個人設定全量PUTの保持、ドラッグindexの回帰を確認。 |
| 10 | 本人+scopeで共有取得キーを分離。切替時の破棄、古いGET/PUT応答の無効化、失敗時のエラー・再試行をcomposableテストで確認。 |
| 11 | scope一括読取、共有1GET。Service呼出回数とRepository SQL統計、FE共有取得テスト。機能ごとのDB/API取得を追加していない。 |
| 12 | 既存業務APIと直URLの認可を変更しない。PUBLIC/個人は対象外。実機の直URLと負の視点を別途確認する。 |
| 13 | 要対応のcirculation/surveys/scheduleを区分ごとに絞り、可視区分だけの合計と全OFF時非表示をcomponentテストで確認。 |
| 14 | 独立2列、保存中/失敗/再試行、復帰導線。51機能の参照ラベルは全6言語に存在し、FE/BEカタログはTEAM38/ORG40で一致。 |

## 検分で修正した点

- 個人非表示と機能非表示が混在する通常gridのドラッグで、表示中indexを全体順序のindexとして渡していた。キーで全体indexへ対応させた。red/greenは[FE検証記録](frontend-validation.md)に記録。
- Entity日時を既存UTC LocalDateTime規約へ合わせた。
- rollback試験の失敗注入順序、参照ロール不足、trigger作成権限を補正し、環境失敗を機能のred証拠に数えない。
- OpenAPIの必須項目と200成功応答を明示し、CI生成物を正本として同期する。

SQLはバインド変数を用い、UPSERTをキー順に実行する。新規依存、独自キャッシュ、既存認可の緩和、個人設定の書換えは追加していない。STRING enum初回導入の限定的な互換性判断は機能仕様の第5節に記録した。

## 残る確認

本記録の静的照合だけを合格証拠にはしない。最新headのCI、実機E2E、3住民の探索結果は各実行結果を確定してから完了判定する。
