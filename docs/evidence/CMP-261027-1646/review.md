# L1-B 裁可後の検分記録（2026-10-28）

対象: PR #4169、実装 head a8b7d8b999973d2c138ffecce2fbe2233f6bb09a。検分者: root Codex、実装担当とは独立。

製品差分について確定指摘0。出荷判定は、以下の承認条件の正本反映と最新main統合CI成功後に確定する。本記録時点では未merge。

完了文書原稿への追記: B5裁可は[PR #4201](https://github.com/kenta-0420/mannschaft/pull/4201)で正本反映済み、[別件1554 / Issue #4200](https://github.com/kenta-0420/mannschaft/issues/4200)は未着手。main統合HEADは `f2817eb94326b413122c69f4b0c409288c8982a6`、[source bridge](build/integration-261028-source-bridge.json)でoriginal L1-B変更11fileのblob一致を確認。[統合CI40648568860](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860)は後続採取で6fresh・失敗0の成功を確認。上記a8b検分を統合後の最終押印へ付け替えず、最終判定はrootが別途確定する。

## 裁可

REQ-GRADLE-DIAG-02 はユーザーの「推奨で。よきにはからえ」で解決。公開診断artifact欠損のgate非成功を今回の挙動不変移設から分離し、別件登録する。https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6462685947 。既存gateはjob結果のみを検査し、artifact存在は保証しない。この不足を検証済みとして扱わない。

## 検分とAC対応

- B-1: root/appとincluded buildの責務、外部plugin版、Java/JaCoCoのBoot前適用、依存scopeとconsumer相対pathを確認。元の非空行と分割後の行照合で意図しない削除0。tasks原本・依存5構成・graph3本のbytes一致を原証拠と照合。
- B-2/B-3/B-4: 実候補37profile/76比較の前後一致、4profile coverage、未指定/空/境界/負hash/同重み/nestedを確認。候補件数はJUnit実行件数とは区別。Stripe変更は候補2pathのみ、28固定/29拒否/欠損failの判定不変、負試験の実XMLを確認。
- B-5: CIと同じ--continueで制御arch故障後のfreeze実XML2件成功、arch0拒否とfreezeclass欠損拒否を確認。continue無しの保証はしない。実gate shellの27入力、regen自己試験6件と旧bytes保全を確認。公開artifact不足は上記裁可で別件化し、wrapper終端欠損拒否と混同しない。
- B-6: 新権限/API/DB変更なし。OpenAPI前後の実生成6,378,658bytes一致、実機4caseの許可/拒否/他tenant/匿名、既存認可テストを落とさないCI配線を確認。
- B-7: 初回全6fresh Test本体の失敗0とshard5後処理timeoutを区別。設定不変の再試行attempt2 success、shard5 FROM-CACHEをfreshと扱わない。JVM/heap/fork/context cap・engine分離維持。性能改善は未実証。
- B-8: 限定Stripe裁可と今回のB5裁可、所有者・入口・資源予約を照合。実機4caseと独立3住民探索を実記録・PNGで確認し、確定製品不具合0。最新main追従と最終CI/merge実体の確認が残る。

## 証拠と限界

実装報告・B7 attempt1/2・real-ui-reviewを参照。旧検証対象はa8bであり、統合後headへ名前だけ付け替えない。移設コードのblob同一性と新headのCIを追加記録する。全画面/全viewportの視覚監査ではなく、探索で個別予定詳細の復帰は未検証。管理側パネル選択前のselector遮蔽、素早い遷移時の一時fetch失敗は原因断定せず記録し、安定探索では継続再現なし。

## main統合後の追記

裁可文書PR #4201 は通常merge済み（b7e4bcd07e2bb93899ea761736bdd6a413e41780）。実装は同mainを通常mergeし f2817eb94326b413122c69f4b0c409288c8982a6 となった。rootはbuild/settings/build-logic/Stripe番人/weights/.githubのa8bからの差分0と、main比較の対象16file維持を確認。source bridgeは変更対象非Markdown11fileのblob同一を記録し、upstream全製品の同一性とは区別する。旧実機/探索はa8bで実施した結果として維持する。

改訂B5のローカル終端欠損拒否は、f281の実wrapper原本を用いたfixture7caseで補足しrootが結果を確認。fresh/FROM-CACHE/UP-TO-DATEは0、NO-SOURCE/終端なし/failed終端+command0は125、command7は7を保持。Gradle/Java/公開artifactの実障害ではない。採取completed=trueでもcommand7は失敗として区別する。

代表PNG3枚はrootが再度画像として確認し、テスト団体名以外の個人名/メール/資格値なし。member通信件数183と保存原本188の差は原因未確定として明記。証拠の成否を変更しない。移設対象コードの追加指摘0、統合CI run40648568860は後続採取で6fresh・失敗0の成功を確認した。さらにdocs-only main追従後のe67 CI40662266445とマージ実体は待機中。

git diff --check のPR全差分では、新規Kotlin2fileの末尾blank行警告がある。a8bから不変で機能影響は確認されず、これをgreenと偽らず記録する。