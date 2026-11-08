# CMP-261028-1554 公開CI診断artifactの必須化（登録・未実装）

追跡: [Issue #4200](https://github.com/kenta-0420/mannschaft/issues/4200)。scope: `tooling`。対象は `.github/workflows/backend-ci.yml` の公開診断artifact検証と、その検証用script・テスト。L1-B（子 CMP-261027-1646、PR #4169）で発見した既存不足を独立して扱う。登録時点では実装・red/green・修正後CIは未実施であり、L1-Bや本件の完了証拠ではない。

## 裁可と現状

2026-10-28のユーザー回答「推奨で。よきにはからえ」により、L1-Bは挙動不変の移設に限定し、公開診断artifact欠損のgate強化を本件へ分離する。[裁可記録（REQ-GRADLE-DIAG-02）](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6462685947)。B-5のその他の条件、B-1〜B-8の残条件、所有・予約・後続入口は変更しない。

調査基準は main `ac49b5af784b79bade786ac0c82b9bb3822289f3`。以下は静的確認であり、公開artifactを削除・破損させた実CIの再現ではない。

- `backend-ci.yml` の「全shardのクラス進捗とJVM診断をアップロード」は `if-no-files-found: warn`。ローカル診断directoryが無ければupload stepが成功し、artifact自体は作成されない場合がある。
- 集約 `Compile & Test` は `needs` のジョブ結果を検査する。失敗・cancel・timeoutに由来するfailure・結果欠損は拒否するが、公開artifactの一覧・内容・全6shardの完全性は検査しない。部分的な診断がuploadに成功しても、それだけでgateを失敗にする仕組みはない。upload action自体がfailureなら既存のジョブ結果判定が失敗を伝えるが、その契約も本件で試験する。
- `backend/scripts/run-ci-tests-with-observability.sh` の通常実行は、Gradle成功なのに有効なTest終端が無い場合にexit125とする。`--finalize` はテストstep結果を記録し、uploadより前に実行される。これらはローカル実行の検証であり、公開後のartifact完全性の検証ではない。
- `backend/scripts/regen-shard-weights.sh` はnightlyの全6fresh診断を検査し、不足・不正入力では再生成を拒否する。PR CI集約gateの検証とは別契約であり、本件のgreen証拠へ流用しない。

## 受け入れ条件

| AC | 条件と必要な試験 |
|---|---|
| D-1 正常 | テスト実行を要するPRで、同一run・head・対象attemptに対応するshard0〜5の公開診断が全件そろい、既存の正常診断形式を満たす場合だけ、他の必須ジョブ成功と合わせて集約gateが成功する。正常6件のfixtureと修正HEADの実CIで確認する |
| D-2 欠損・空 | 公開artifactが全件無い場合、6件中1件無い場合、空archive、空の必須ファイルをそれぞれ独立検体で拒否する。uploadのwarnや成功stepだけを公開成功の証拠にしない |
| D-3 部分欠損 | archiveが存在しても必須診断ファイルの一部欠損・不正JSON・途中切断を拒否する。現行producer/consumerから必須ファイルと各実行結果ごとの有効形式を実装前に列挙し、各欠損を試験する。任意のheapdumpが無いことは欠損扱いにしない |
| D-4 途中失敗 | upload失敗・cancel・timeout、artifact一覧/取得APIのエラーや検証処理の失敗を成功へ倒さず、集約gateを非成功とする。既存のテスト失敗/cancel/timeoutの拒否も維持する |
| D-5 境界・取り違え | 別run/head/attemptの同名・旧artifactで不足を補わず、shard重複・範囲外も拒否する。失敗ジョブのみ再試行した場合はGitHubが継承する成功ジョブと再試行ジョブの対応を明示的に検証し、無関係な世代を混ぜない |
| D-6 互換 | docs-only skip成功、正当なtest cache hit、既存6shard・engine/tag/filter・heap/fork/context設定を維持する。cache hitをfresh実測として強制しない。Test終端・ローカル診断検証と公開artifact検証の結果を区別し、夜間重み再生成の拒否条件を緩和しない |
| D-7 証拠・負荷 | 検証の対象run/head/ジョブ世代/shardと失敗理由を安全なログに残し、secretや診断内容全体を出力しない。取得範囲を対象6shardに限定し、待機/再試行に有限上限を設ける。各負試験の先行red→green・同HEAD成功CI・検分を記録する |

攻め口5類型: 空/0/null相当はD-2/3、境界はD-5/6、途中失敗はD-4。認可はGitHub Actionsの既存権限境界を維持し、PR由来コードをtrusted権限で実行しない（D-7の検分で確認）。アプリの認可/API/DBは対象外。性能はD-7の限定取得・有限待機を試験し、アプリ性能改善を主張しない。

## 着手順と境界

1. 本登録をmainへ反映する。実装担当・共有CIの予約を既存ownerと調整してから状態を実装中へ更新する。本登録だけでL1-Bの予約を取得・変更しない。
2. 現行診断producer/consumer、GitHubの再試行時のジョブ継承、artifact名と公開時点を照合し、D-1〜D-7の検体と必須ファイルを確定する。
3. 既存不足を示すredを保存し、最小の公開診断検証とgateへの接続を実装してgreen化する。Gradle/製品コード・テスト選別・時間制限の変更は含めない。
4. 同HEADの実CI、検分、toolingとしての完了証拠を別PRで残す。L1-Bの移設完了と本件の修正完了を別々に判定する。
