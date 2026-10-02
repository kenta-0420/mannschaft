# CMP-260820-1018 代理入力同意管理

- ブランチ: `feature/cmp-260820-1018-consent-management-20261002`
- 基点: `b3efd80c58`
- 担当: 足軽B（Sol / medium、Terra代替）。軍議・成果検分・出荷判断は殿。
- 状態: 訂正前の実DB契約45件を実行し33失敗、skipped/errorsは0。前任裁可に合わせた訂正・同意ページング追加中。実装・green・実機は未実施。

## 方針

前任引継（引継-cmp-260820-1018-proxy-input-admin-20261002-0825.md）のユーザー裁可を正本として、`/admin/proxy/consents` と `/admin/proxy/records` を追加する。組合選択/currentScopeは既存金型を使い、組合管理ハブ配下の新pathは作らない。組合管理資格は既存ADMIN/DEPUTY_ADMIN、承認はPROXY_CONSENT_APPROVEで判定し自己承認を禁止する。SYSTEM_ADMINはBE APIで組合横断を許可し、通常組合業務UIの導線は表示しない。一般isAdminOrAbove helperの意味は変えずproxy入口で明示例外を適用する。新テーブル・DDL・権限追加は行わない。

実操作履歴は既存recordsと同意書をDB JOINし、organizationIdと任意subjectUserIdをAND条件にして標準PagedResponseへ返す。組合指定なしは本人（既存SYSTEM_ADMIN例外を維持）。同意書IDなしの後見切替は本人履歴で表示し組合履歴から除く。

同意一覧も標準PagedResponseでDBページングする。scopesのcollection fetchをPageable queryへ直接付けてメモリ内ページングへ倒さず、IDページ取得後のbounded graph fetchを使う。size101は旧試練どおり200で100へ制限し、下限未満とpage負値は400とする。

## 受け入れ条件と証跡

殿から受領したAC1〜11を対象とする。管理入口・全状態一覧、承認状態制約、紙撤回保存、履歴の組合/本人交差、空/取得失敗/再試行、null表示、理由255/256・ページ境界、未認証/越境、途中失敗、DBページング/N+1、6言語/390px/権限別実機と探索3視点を検証する。

- red: Windows陣からの初回実行はcompileJavaのソースfingerprintで45分超停滞したため自所有処理のみ中断。ext4 snapshotへcommit912ace7eのbackend blobs一致を確認して再実行し、5 XMLで45件/33 failures/0 skipped/0 errors（10分16秒）。旧SYS拒否期待は最終正解に計上しない。履歴仮応答、状態上書き、未知method500、親削除後操作、OSIV=false下の一覧/承認500を実測した。紙保存nullの2件は外側テストTXのflush前clearが原因と追加精査で判明し、本体不具合へ計上せず試験を訂正する。
- 前任trial commit92e7ef7787ff0c1b921f4e38fe0e97ecabc1b091の17件と引継を全文照合し、SYS横断・同意一覧page/size/emptyを現在の専用fixtureへ移植。旧非本人API撤回正常系は主体資格を維持しつつF14.1§350/351に従いPAPER＋同組合ADMIN証人のpayloadへ更新する。DEPUTY承認権限のfixtureはF01.2のgroup由来契約へ訂正（role既定権限は無効だった）。
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
| 組合同意一覧・組合指定履歴 | ACTIVEな当該scopeのADMIN/DEPUTY_ADMIN、またはSYSTEM_ADMIN（組合未所属でも横断可）。管理UIは最強roleName ADMIN/DEPUTY_ADMINのみ、SYSは導線なし。 |
| 組合指定なし履歴 | 本人。既存SYSTEM_ADMINの他subject指定例外は維持。 |
| 承認 | PROXY_CONSENT_APPROVEまたはSYSTEM_ADMIN、かつ代理者本人以外、pendingのみ。一般scope helperの変更はしない。 |
| API_BY_SUBJECT撤回 | 同意対象本人。組合の現在の在籍は問わず、退会後も維持。 |
| PAPER_BY_SUBJECT撤回 | 既存scopeADMIN/DEPUTY_ADMIN操作資格またはSYSTEM_ADMIN。立会資格のADMIN限定/DEPUTY包含はユーザー判断待ち。立会人は有効userと同じ組合の資格を要する。actorと同一であることは強制しない。 |
| AUTO方法の手動撤回 | 拒否。 |

approve/revokeは同意実体から組合を解決し、親不在・論理削除をGateより先に確認する。親不在/削除、同意不在、越境は既存COMMON_002（403）に揃える。同scope権限不足も既存403を保持する。組合の公開QueryService.findSummariesByIdsを非TX facadeから呼び、他ドメインRepository参照や既存cross-domain TX凍結の拡大を避ける。

追加redにはSYS単独/併有・DEPUTY承認権限の有無・組合退会後本人・無効立会人・親削除・mutation未認証・空/size/page境界・同日時id降順・entity取得件数とSQL数の定数性を含む。UI固有の空/再試行/成功後再取得失敗・null・6言語/390pxは後段で検証する。

application.yml:54のopen-in-view=falseを実確認。同意のscopesはLAZYで既存ControllerはService TX後にDTOへ変換するため、非@TransactionalのSerializationContractITを追加した。fixtureの確定と自分のIDだけの後始末はTransactionTemplateで行い、一覧10件・active・承認のHTTP応答を実TX外でserializeする。scopesのcollection fetch countも検証して一覧のN+1を固定する。同じSpring context金型を使い独自profile/property/contextは追加しない。

訂正後の候補は60件（旧45＋同意一覧12＋非TX DBページ件数1＋並行mutation2）。並行試験は実MySQLの先行TXが同意1行をPESSIMISTIC_WRITEで保持して撤回情報をflush/refreshし、後続HTTPのfindByIdForUpdateまたは旧flush経路とMySQL read待機stackを観測してから先行TXをcommitする。任意sleepを使わず10秒の観測期限/30秒のlock保持期限と自所有executor解放を設け、HTTP409・先行撤回日時/理由/証人の不変性・承認日時nullを検証する。まだ実測前でありgreen扱いにしない。
