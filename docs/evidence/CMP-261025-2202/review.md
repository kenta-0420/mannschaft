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
- Entity日時を既存LocalDateTime規約へ合わせた初回判断は、全量CIで新規日時型方針の番人が失敗したため撤回する。規約矛盾と是正結果は[宣言・日時型の検証記録](architecture-validation.md)へ記録する。
- rollback試験の失敗注入順序、参照ロール不足、trigger作成権限を補正し、環境失敗を機能のred証拠に数えない。
- OpenAPIの必須項目と200成功応答を明示し、CI生成物を正本として同期する。
- 実機で、ホームから共有cacheを再利用して設定画面へ遷移すると設定行が0件になる不具合を発見。即時watchの初期化順により取得済みdraftを空へ戻していた。refreshで値を変更しないcache-hit回帰でredを確認し、現在scopeの取得済み設定からdraftを初期化してgreenを確認した。通常Nuxt経路も`b19131bff0`のFrontend CIで成功した（703ファイル・6,934件成功、既存skip 5件）。
- 実APIのTEAM/ORGダッシュボードは`viewer_role`を返し、新しいPanelは未変換の`viewerRole`を読んでいたため、スワイプの表示方針が適用されなかった。MEMBER/SUPPORTERの実応答4件とDTO/クライアントを照合し、2取得APIの境界でロール項目だけを正規化した。同じ実TSmoduleの2回帰でred→greenを確認。[wire検証記録](dashboard-wire-validation.md)を参照。
- 非所属者のTEAM設定直URLが空画面に残る既存ガードを実証。`null`を未解決と混同していたため、既存の権限解決成功フラグで判定するよう補正。実SFCのresolved-null回帰でred→greenと他4境界の保持、通常UIでhome復帰・設定要素0件を確認した。ORGは既存middlewareで復帰するため変更しない。
- API宣言番人のネストしたSwagger注釈の走査で、class mappingが余分なHTTPメソッドへ誤帰属していた。括弧の平衡を追う既存処理へ注釈名直後から渡し、合成自己テストを追加した。新API4本には認可を維持した制御面の常時到達理由を明示し、未宣言枠を増やさず台帳へ登録した。全production走査の変化は新2クラス各3→2だけで、既存クラスは不変。詳細と正式検証は[宣言・日時型の検証記録](architecture-validation.md)へ記録する。

SQLはバインド変数を用い、UPSERTをキー順に実行する。新規依存、独自キャッシュ、既存認可の緩和、個人設定の書換えは追加していない。STRING enum初回導入の限定的な互換性判断は機能仕様の第5節に記録した。

## 最終判定：合格

実装headは`609ee90bf65af64b17d95e9ee31d27953c5abdaa`。mainの交代承認修正を1回取り込み、[Backend CI 40246540422](https://github.com/kenta-0420/mannschaft/actions/runs/40246540422)の全6shard・集約gate成功をAPIと原本ログで裏取りした。[対象6クラスとMFA実測](backend-final-ci-results.json)も親が照合した。Frontend CI 40246540366は705ファイル6,941件・後続27件成功（既存skip5件）、Smoke・OpenAPI/生成型を含む26チェック成功、週次全履歴スキャンとmain限定Dockerビルドの2件だけ条件付きskip。

AC14件、正常・空/null・境界・途中DB失敗・認可・一括取得を実装とgreenテストへ対応付けた。TEAM/ORG実機2件・3住民探索は成功し、[探索の統合判定](exploratory.md)へ代表画像・寸法と改善候補を記録した。実機後の内部日時型・宣言番人変更は実MySQL50件と最新全量CIで担保した。MFAの一度の閾値超過と計測変動は[調査記録](ci-performance-validation.md)に残し、原因をCPU競合だけに断定せず、閾値や本体を変更していない。

残存する新規指摘0件。既存UIの4観測は未検証改善候補であり、本機能の阻害欠陥へは昇格していない。実機のNode24と正式CIのNode22の環境差、実機sourceの固定はFE記録に明記した。本番性能や長期のMFA計測安定性は今回の試験だけでは保証しない。

PR #4116は2026-10-26T01:40:43ZにMERGED、merge commit `cfe79e0fa868af185f2c3274b5c00f8c6bf505f2`をorigin/mainで確認した。admin使用の根拠は、main追従1回後に台帳だけが先行し、PR74ファイルとの重複0、MERGEABLE、最新headの全必須CI成功を再確認したこと。凱旋規則の追従例外を適用した。
