# CMP-260820-1018 代理入力同意管理

- ブランチ: `feature/cmp-260820-1018-consent-management-20261002`
- 基点: `b3efd80c58`
- 担当: 足軽B（Sol / medium、Terra代替）。軍議・成果検分・出荷判断は殿。
- 状態: 実DB契約テストを45実行候補（パラメータ展開を含む）まで追加。実装・green・実機は未実施。

## 方針

組合管理ハブ配下に同意一覧・承認・紙撤回・実操作履歴を備える1ページを追加する。組合管理資格は既存ADMIN/DEPUTY_ADMIN、承認はPROXY_CONSENT_APPROVEで判定し自己承認を禁止する。管理UIは既存裁可に従い最強ロールADMIN/DEPUTY_ADMINのみ表示する。新テーブル・DDL・権限追加は行わない。

実操作履歴は既存recordsと同意書をDB JOINし、organizationIdと任意subjectUserIdをAND条件にして標準PagedResponseへ返す。組合指定なしは本人（既存SYSTEM_ADMIN例外を維持）。同意書IDなしの後見切替は本人履歴で表示し組合履歴から除く。

## 受け入れ条件と証跡

殿から受領したAC1〜11を対象とする。管理入口・全状態一覧、承認状態制約、紙撤回保存、履歴の組合/本人交差、空/取得失敗/再試行、null表示、理由255/256・ページ境界、未認証/越境、途中失敗、DBページング/N+1、6言語/390px/権限別実機と探索3視点を検証する。

- red: 取得契約・認可7件、承認・撤回14件を作成。Repository.saveを使う専用fixtureへ整理済み。初回compileJavaのソース入力fingerprintが45分超継続しテスト未到達のため、殿の指示で自分の処理だけを中断。red成立・XML件数は未確認。ext4の専用snapshotへ同じcommitを取り込み再実行する。
- green・関連回帰・生成型・lint/typecheck: 未実施。
- 実機・E2E・アリシゼーション: 未実施。

## 進行上の制約

本陣・既存8080/3000・ユーザー資産は変更しない。heavy Gradleは交通整理スクリプト経由。FE編集は環境担当の既存アンケート実機終了後に開始する。規約の正本は既存文書を参照し本台帳へ複製しない。

## 2026-10-02 保全時点

- CMP-260912-1526は既存PR #3282/#3291と再生成手順書の完走証跡で解消済みと殿が判断し、OpenAPIを理由とする本体/API変更の保留を解除。完了前に標準generateOpenApiDocsを再実行する。
- 専用陣のBE/FE本体は未変更。origin/mainのa21dfa38c84への追従は未実施。
- redのunified exec session3650をCtrl-Cで中断（exit1）。WSL wrapper PID1145134・daemon PID1145221の不存在と共通gate /home/kenta/gradle-turnstile/turnstile.lock.d の不存在を確認。既存アプリやDBへは触れていない。
- 自分daemonのThread.print（14:43:35）ではExecution workerがFileInputStream.open0→DefaultFileHasher→DirectorySnapshotter→DefaultSourceDirectorySet→DefaultInputFingerprinterで待機。コンパイラやHTTP依存取得のスタックはない。Windows worktreeをWSLから読む際のI/Oが原因候補であり、テスト失敗と判断しない。
- ローカル専用run-cmp-consent-red.shとcmp-consent-thread-diagnostic.txtはignore配下、commit対象外。実行scriptはGit環境変数をWSLパスへ設定し、既存turnstileのCRLF除去済み一時コピーを使う。共有ゲートの解放を確認するまで別heavy実行・サーバー起動を行わない。
- 親はremote compactのmodel capacityエラーから復帰し通常作業を再開。紙撤回の立会資格をADMIN限定とするかDEPUTYも含めるかはユーザー判断待ち。非null・有効user・同じ組合資格の検証はどちらでも必要なため、ADMIN正常系と非管理/別組合/不在witness拒否を先行テスト。DEPUTY witnessを推測許可しない。

## エンドポイント別の許可主体

| 操作 | 許可主体と条件 |
| --- | --- |
| 組合同意一覧・組合指定履歴 | ACTIVEな当該scopeのADMIN/DEPUTY_ADMIN。SYS単独は不可、scopeADMIN併有の既存API資格は維持。管理UIは最強roleName ADMIN/DEPUTY_ADMINのみ。 |
| 組合指定なし履歴 | 本人。既存SYSTEM_ADMINの他subject指定例外は維持。 |
| 承認 | PROXY_CONSENT_APPROVEかつ代理者本人以外、pendingのみ。GateのADMIN/SYS通過だけでは許可しない。 |
| API_BY_SUBJECT撤回 | 同意対象本人。組合の現在の在籍は問わず、退会後も維持。 |
| PAPER_BY_SUBJECT撤回 | 既存scopeADMIN/DEPUTY_ADMIN操作資格。立会資格のADMIN限定/DEPUTY包含はユーザー判断待ち。立会人は有効userと同じ組合の資格を要する。actorと同一であることは強制しない。 |
| AUTO方法の手動撤回 | 拒否。 |

approve/revokeは同意実体から組合を解決し、親不在・論理削除をGateより先に確認する。親不在/削除、同意不在、越境は既存COMMON_002（403）に揃える。同scope権限不足も既存403を保持する。組合の公開QueryService.findSummariesByIdsを非TX facadeから呼び、他ドメインRepository参照や既存cross-domain TX凍結の拡大を避ける。

追加redにはSYS単独/併有・DEPUTY承認権限の有無・組合退会後本人・無効立会人・親削除・mutation未認証・空/size/page境界・同日時id降順・entity取得件数とSQL数の定数性を含む。UI固有の空/再試行/成功後再取得失敗・null・6言語/390pxは後段で検証する。

application.yml:54のopen-in-view=falseを実確認。同意のscopesはLAZYで既存ControllerはService TX後にDTOへ変換するため、非@TransactionalのSerializationContractITを追加した。fixtureの確定と自分のIDだけの後始末はTransactionTemplateで行い、一覧10件・active・承認のHTTP応答を実TX外でserializeする。scopesのcollection fetch countも検証して一覧のN+1を固定する。同じSpring context金型を使い独自profile/property/contextは追加しない。
