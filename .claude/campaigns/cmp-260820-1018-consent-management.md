# CMP-260820-1018 代理入力同意管理

- ブランチ: `feature/cmp-260820-1018-consent-management-20261002`
- 基点: `b3efd80c58`
- 担当: 足軽B（Sol / medium、Terra代替）。軍議・成果検分・出荷判断は殿。
- 状態: 実DB契約テストを先行作成中。実装・green・実機は未実施。

## 方針

組合管理ハブ配下に同意一覧・承認・紙撤回・実操作履歴を備える1ページを追加する。組合管理資格は既存ADMIN/DEPUTY_ADMIN、承認はPROXY_CONSENT_APPROVEで判定し自己承認を禁止する。管理UIは既存裁可に従い最強ロールADMIN/DEPUTY_ADMINのみ表示する。新テーブル・DDL・権限追加は行わない。

実操作履歴は既存recordsと同意書をDB JOINし、organizationIdと任意subjectUserIdをAND条件にして標準PagedResponseへ返す。組合指定なしは本人（既存SYSTEM_ADMIN例外を維持）。同意書IDなしの後見切替は本人履歴で表示し組合履歴から除く。

## 受け入れ条件と証跡

殿から受領したAC1〜11を対象とする。管理入口・全状態一覧、承認状態制約、紙撤回保存、履歴の組合/本人交差、空/取得失敗/再試行、null表示、理由255/256・ページ境界、未認証/越境、途中失敗、DBページング/N+1、6言語/390px/権限別実機と探索3視点を検証する。

- red: 取得契約・認可の7件を作成し実行中。初回compileJavaのソース入力fingerprintが45分超継続し、テスト未到達。red成立・XML件数は未確認。現在のfixtureはEntityManager.persistであり、Repository.saveを使う規約の金型へ整理した上で承認・撤回の契約テストを追加する。
- green・関連回帰・生成型・lint/typecheck: 未実施。
- 実機・E2E・アリシゼーション: 未実施。

## 進行上の制約

本陣・既存8080/3000・ユーザー資産は変更しない。heavy Gradleは交通整理スクリプト経由。FE編集は環境担当の既存アンケート実機終了後に開始する。規約の正本は既存文書を参照し本台帳へ複製しない。

## 2026-10-02 保全時点

- 本体/API変更は殿からのOpenAPI依存調査指示で保留中。CMP-260912-1526は既存PR #3282/#3291と再生成手順書の完走証跡があると調査報告済み。保留解除は未受領。
- 専用陣のBE/FE本体は未変更。origin/mainのa21dfa38c84への追従は未実施。
- redのunified exec sessionは3650、WSL wrapper PID1145134、daemon PID1145221。停止していない。
- 自分daemonのThread.print（14:43:35）ではExecution workerがFileInputStream.open0→DefaultFileHasher→DirectorySnapshotter→DefaultSourceDirectorySet→DefaultInputFingerprinterで待機。コンパイラやHTTP依存取得のスタックはない。Windows worktreeをWSLから読む際のI/Oが原因候補であり、テスト失敗と判断しない。
- ローカル専用run-cmp-consent-red.shとcmp-consent-thread-diagnostic.txtはignore配下、commit対象外。実行scriptはGit環境変数をWSLパスへ設定し、既存turnstileのCRLF除去済み一時コピーを使う。共有ゲートの解放を確認するまで別heavy実行・サーバー起動を行わない。
- 親がremote compactのmodel capacityエラーで中断、環境担当も終了状態となったため、現時点のIT・台帳を保全commitする。完了ではなく再開待ち。
