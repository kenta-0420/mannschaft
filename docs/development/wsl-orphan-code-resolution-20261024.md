# WSL孤立コードの正本照合（2026-10-24）

Issue [#4096](https://github.com/kenta-0420/mannschaft/issues/4096) / CMP-261024-2100 の調査記録。登録PR [#4097](https://github.com/kenta-0420/mannschaft/pull/4097) はマージ済み。対象の採否と不要コピーの撤去を完了した。Ranch3コピー・home11コピー・旧archive77本に加え、追加9 expanded sourceの20ディレクトリと22 backend buildも撤去し、不存在を確認した。未完の製品開発は既存PR/CMPへ対応付け、元の状態を維持する。

## 比較の基準

正本の台帳・仕様・完了証拠は取得済みの `origin/main` から読む。本陣の古いworking treeで未完了を判定しない。Ranch詳細監査の比較先は `783a391314bc9bd42f754b872bb331684d09269e`、台帳の初回確認先は `ba299bd9ab4d1f7e0b655758ccc91994196ae71e`。その後の正本確認ではmain `0a16a66185` までの更新を対象とする。

非祖先のcommitや現mainと異なるblobがあることだけを未反映の証拠にしない。正本ref・PR・patch-id・改行正規化本文と後続改善を確認する。tracked以外のspec・fixtureも対象とする。実行時の絶対パス、秘密設定、全履歴ref一覧は公開文書に転載しない。

## 確認済みの採否

| コピー・差分 | 正本との対応 | 採否と根拠 | 処置・次の一手 |
|---|---|---|---|
| Ranch `integration-verified-4bc159362fd0` | Ranch実装 [#3652](https://github.com/kenta-0420/mannschaft/pull/3652)、完了証拠 #3909 | 検証用Git初期化snapshot。旧 `RanchDevelopmentPolicyIT` を除きblobはmain履歴に存在。旧試験のcleanupは現mainでrecipient purge・transport削除・TX内削除へ強化済みのため追加採用不要 | 原コピーを撤去し、不存在を確認済み |
| Ranch `integration-verified-72bbaffe8bd5` | 同上 | HEADと残refはmain祖先。ignoredなlogs内のwidget persistence specも正式試験と改行正規化後に同一。必要な未反映コードなし | 原コピーを撤去し、不存在を確認済み |
| Ranch `pending-resume-source-20261008-02` 全域 | 同上。診断関連はmain祖先 `14b592ea2d` にも照合 | overlay19件は14件が現mainと正規化同一、2件が採用後更新済み、3件が後続改善に置換済み。全域を追加照合し、旧生成OpenAPIは旧専用path・operationIdとも0でpending操作の応答契約も現mainと一致。生成Java62件・コピーSQL1213件・report JS5件にも独自ソースなし | 最小proofだけを保存し、原コピーを撤去。不在を確認済み |
| home Ranch clipsの3ファイル | 現mainの同名ファイル | 全件が現mainと完全一致。追加採用不要 | home11コピーは全撤去・不存在確認済み |
| home friendの旧試験4件 | 現mainの強化済み試験 | 旧版へ戻す必要なし | home11コピーは全撤去・不存在確認済み |
| home typingの2ref | 現main・既存の正本PR/ref | 追加復元不要。既存正本に対応付ける | home11コピーは全撤去・不存在確認済み |
| homeの追加検証ファイル10件（blogui1・monsho1・l13 3・orggroup5） | 現main・既存の正本PR/ref | 全10件を追加比較し復元不要。typing2refとは別集計 | home11コピーは全撤去・不存在確認済み |
| 旧rescue戦役 CMP-261006-0421 | [完了証拠](../evidence/CMP-261006-0421.json)、PR #3750・#3758 | 最新mainでは2026-10-10完了。6件の採否・撤去も記録済み。本陣の古い2026-10-09時点の未着手表示を根拠に再開しない | この戦役への追加回収・再開なし |

Ranchの対象3コピーに必要な未反映コードはなかった。これはRanch Phase1の正式公開や全ACの完了判定とは別であり、公開等の残件は既存の正本で管理する。

## 旧archive77本の最終採否

77本（旧copy62・追加15）の293 source rootとnested tar27本を照合した。機械的な初回分類をそのまま未確定数には使わず、手書きソース273hashを次の8群に分けて意味を確認した結果、必要なonly-archiveソースは0件。新しい回収branch/PRは作成していない。

| 群（hash数） | 正本PR・CMP | 最終採否 |
|---|---|---|
| 検証・診断補助（131） | 元source HEADと局所結果の最小provenance | 旧検証目的が終了。製品featureに追加するコードではなく、failed/notformalを区別して記録し退役 |
| 代理アンケート認可（21） | #3616 / CMP-261003-1020 | 必要差分は既存Draftで保持。局所149greenと当時の全CI OOM・実機未完を分け、現mainのbug有無は断定しない |
| 代理入力管理（55） | #3595 / CMP-260820-1018、旧 #3609 | 現mainの管理機能が正本。旧統合案は台帳どおり原形保全し、復元・再開しない |
| Oracle試験（4） | #3661・#3681 / CMP-260821-1027 | Receipt限定変更は採用済み。旧試験は現行契約に置換済み。元16domain全体の未完了は維持 |
| お知らせpreview（3） | #3658 / CMP-261004-2309 | 正本は完了済み。旧probe・旧版の追加復元不要 |
| シフト遷移（4） | #3629 / CMP-260903-0658 | 必要差分は既存Draftに存在。元5遷移featureの未完了を維持 |
| シフト版管理（41） | #3724・#3726 / CMP-261008-1253・CMP-261008-1407 | 必要差分は既存Draftに存在。現mainの後続改善を巻き戻さず、競合・実機等の残件を維持 |
| アクティビティ自動処理（14） | #3729 / CMP-261008-1203 | 正本は完了済み。旧listener・DTO版の追加復元不要 |

現役Draft #3616・#3629・#3724・#3726・#4009は保護し、元featureの未完了をコピー整理で完了に変更していない。

## 既存の未完了正本

| 正本PR | 元戦役・位置付け | 今回の整理での扱い |
|---|---|---|
| [#3724](https://github.com/kenta-0420/mannschaft/pull/3724) | CMP-261008-1253、既存Draft | 必要ソースは既存正本に存在するため新回収PRを重複作成しない。元CMPの未完了状態と次の一手を維持 |
| [#3726](https://github.com/kenta-0420/mannschaft/pull/3726) | CMP-261008-1407、既存Draft | 競合・実機未完を含む元CMPの残件を維持。コピー整理を機能完了に読み替えない |
| [#3629](https://github.com/kenta-0420/mannschaft/pull/3629) | CMP-260903-0658、既存Draft | 必要ソースは既存正本で保護。新回収PR不要、元CMPの残件を維持 |
| [#3616](https://github.com/kenta-0420/mannschaft/pull/3616) | CMP-261003-1020、既存Draft | 必要ソースは既存正本で保護。本整理で元ID・AC・実装中の独立行を表末尾へ復元。旧PRの未完記録であり、現在のbug有無・機能完了を断定しない |
| [#4009](https://github.com/kenta-0420/mannschaft/pull/4009) | CMP-261011-0035、既存Draftのtest-only PR | 同CMP・既存PRを正本として保護。今回の整理だけで製品修正や戦役完了を断定しない |
| [#3609](https://github.com/kenta-0420/mannschaft/pull/3609) | main台帳のCMP-260820-1018に旧履歴・現在進行対象外・原形保全と明記 | PRを閉鎖・再開せず原形保全を維持 |

## Ranchの撤去結果

実行直前に各コピーのrealpath・device/inode・所有者・全体inventory fingerprintが計画と一致することを確認した。全procのcwd/fd/exe/maps/cmdlineとmountinfo、停止中を含むDocker53コンテナのbind、GitのcommonDir・alternates・登録worktree、同じ限定探索範囲の逆Git pointer207候補を再確認し、参照・検査不能・境界変更がない対象だけを撤去した。

3子ディレクトリはすべて不存在を確認済み。撤去したファイルの割当容量は合計 **1,327,251,456 bytes**。Dockerの前後の稼働状態・起動時刻・PIDは一致し、親Ranchディレクトリも存続している。DB・サービス・他のWSL root・共有C Git objectは操作していない。

保存したのは採否・identity・結果のJSONと最小proofであり、全量backupは追加していない。最小proofは過去の選択試験27件のgreen、`fullGreen=false`、bootJar buildの終了0、試験・server未起動を区別して記録する。今回、新たに製品のbuild/testを実行した証拠として扱わない。

## home・archiveの撤去結果と親確認

home11コピーは全撤去・不存在確認済みで、保持・errorは0件。推定割当容量は **3,919,519,744 bytes**、filesystem空き容量の実測増加は3,919,466,496 bytesだった。Docker53コンテナの前後設定・サービス状態は一致した。追加source保全は0件、私用BC10診断2ファイル（203,101 bytes）だけをprivate領域へ保存し、Windows側からもSHAを再確認した。不要なconfig・cookieの新規保全は0件。

旧archive77本も全撤去・不存在確認済みで、保持0件。元manifestとSHA・statの不変、全proc/mount、停止中を含むDocker bindを実行直前に確認し、割当容量 **11,447,758,848 bytes** を撤去した。元parent・正本・正式L0M・サービスを保護し、Dockerサービスの前後状態は不変だった。

Ranch3＋home11＋archive77本の撤去した割当容量は16,694,530,048 bytes。追加source・buildを含む今回のソース照合後の撤去合計は **20,300,267,520 bytes（約18.91 GiB）**。親担当の最終再確認でも対象133pathはすべて不存在（残存0件）、同じ既存7コンテナは稼働を継続した。正式L0M/Q9・本体・既存実行環境・共有C Gitは実在を確認して保護した。WSLのdf実使用は83,074,048,000 bytes（約77.37 GiB）。fstrimは終了0で成功したが、WSL停止・オフライン物理圧縮は実施しておらず、Windows側VHDXの155,813,150,720 bytesは不変だった。Cドライブ空き容量は外部活動の影響もあるため、今回削除の回収効果とは扱わない。これらは環境整理の実測であり、元featureの実機受入れ・製品CI合格とは区別する。全量archiveは追加していない。

## 追加source・buildの監査と撤去

追加9 expanded sourceはsource treeがmain履歴にすべて存在し、実ファイルも最終照合して復元不要と確定した。20 exactディレクトリを撤去し、割当容量2,449,125,376 bytesの退役と全件不存在を確認した。9親ディレクトリとretainPathsは保護し、Docker・systemd状態は前後で一致した。

22 backend buildも旧途中失敗proofとして採否を確定し、22件すべてを撤去・不存在確認した。保持0件、割当容量1,156,612,096 bytes。parent・backend・restore markerは存続し、markerのhashも不変、サービス状態も不変だった。旧JUnit XMLはhash・size・suite件数・失敗名/種別・source HEADのscalarへ縮約し、raw全保全0件・例外0件。正式L0M/Q9の対象とは分けて扱う。追加監査中の対象は残っていない。

台帳は、CMP-261003-1020の元ID・AC・実装中を復元し、旧PRの未完理由と再開手順を対応付けた。CMP-260910-1557は未着手・元AC・元症状を維持し、証拠欄へ旧固定環境specの退役と、2桁・月末の逐字入力、表示/送信/再取得一致、区切り正規化、カレンダー維持を専用fixtureで確認する次の手順を記載した。既存manualInput単体を実画面保存成功へ昇格していない。新しい機能実装・製品の自動確認は実施していない。

## 証跡

根拠はローカルruntimeの `ranch-orphan-code-audit.json`、`ranch-exact-deletion-plan.json`、`ranch-exact-deletion-result.json`、`ranch-minimal-proof-results.json`、`home-orphan-code-audit.json`、`home-exact-deletion-result.json`、`home-external-git-consumer-audit.json`、`home-all-pid-mountinfo-audit.json`、`archive-code-provenance-public-summary.json`、`archive-sha-verification.json`、`archive-exact-deletion-result.json`、`hosted-source-retirement-result.json`、`ranch-retained-build-deletion-result.json`、`parent-retirement-recheck.json`、`final-orphan-cleanup-recheck.json`。公開文書にはprivate診断本文・秘密設定・絶対homeパス・全履歴ref一覧を載せない。
