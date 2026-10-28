# L1-B 完了登録用の最小build証拠

本directoryはsupportの最小build証拠を完了登録用の文書原稿へ採用したものである。元の比較・負試験・実機の対象は実装HEAD `a8b7d8b999973d2c138ffecce2fbe2233f6bb09a`（移設前/旧重み後のSHAは比較JSON・実装報告に記載）。最新main `b7e4bcd07e2bb93899ea761736bdd6a413e41780` を通常mergeした統合HEADは `f2817eb94326b413122c69f4b0c409288c8982a6`。

f281の[Backend CI40648568860](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860)は全6freshで成功し、[統合CI報告](../integration-ci.md)とsafe summaryを採用した。その後docsのみのmainを通常追従した実装HEADはe67。[非docs全treeの一致](integration-e67-source-bridge.json)を確認し、[e67 CI40662266445](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445)は6cacheで成功、PR4169も通常merge済み。a8bの成功CIや旧実機を統合HEADで再実施したとは扱わない。完了JSONは最終実装HEADと同HEADの成功CIを参照し、元の比較/実機/探索の対象SHAを併記する。

| 条件 | ファイルと証拠範囲 |
|---|---|
| B-1 | `comparison/*raw-comparison-partial.json`。名前はpartialだが、各9本・全equalを確認。tasks stdoutを整形せずbytes一致、5依存graph・3実行graphも一致 |
| B-2/B-3/B-4 | `comparison/*candidate-comparison.json`。旧/最終重み各37profile・76比較一致。実Gradle候補/設定・class/helper境界の行数とSHA。`weight-assignment-change.json`は重み変更による移動を別差分として示す |
| B-3/B-5 | `negative/`のXML要約3本。Stripe29/宣言欠損は各tests1/failures1の期待red、制御arch故障後のfreezeはtests2/failures0。XMLのsystem streams/properties/stack tracesは省き、原本SHAをmanifestへ記録 |
| B-3/B-5/B-6 | `negative-and-openapi-observations.json`。archTest0件、freezeclass欠損の固有理由・復元、CIと同じ `--continue` 条件でfinalizer実走、同port18072でOpenAPI実生成前後6,378,658bytes一致を実原本からassert。原本ログ/JSONはhashのみ保持 |
| B-5 | `gate-input-evidence.json`は既存gate shellのneeds入力27ケース。公開artifact欠損検査の証拠ではない。実GitHub matrixで各失敗を発生させたとも主張しない |
| B-7 | `b7/attempt1-*`はa8bの6本fresh Test本体・初回shard5正常終端後の105分step timeout・全XML失敗0・heapdump無しの証拠。`b7/attempt2-retry-summary.json`は失敗ジョブのみ再試行しcache hitで完走、集約successを別記。fresh計測へ混ぜない |
| 重み | `weights-classification-summary.json`は3111計測の分類と現在source非掲載集合の件数/静的分類限界。大きな全class行は省略し原本SHAを維持。nightly metadataは重み入力で、B7最終CIの計測と別物 |
| source対応 | `integration-261028-source-bridge.json`。original L1-B変更の非Markdown11fileがa8b→統合HEADで全blob一致。upstream製品/テスト/凍結store/画面の変更まで同一と証明するものではない |

公開診断artifact欠損gateの既存不足は[裁可](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6462685947)により[別件 CMP-261028-1554 / Issue #4200](https://github.com/kenta-0420/mannschaft/issues/4200)へ分離済み。条件修正は[PR #4201](https://github.com/kenta-0420/mannschaft/pull/4201)でmain反映済み。ローカルTest終端検証、nightly再生成拒否、公開artifact完全性は別契約である。本パッケージは公開診断gateを強化済み・既存不足解決済みと主張しない。

`manifest.json`はコピー/要約の対応、採取時の原本pathとbytes/SHAを記録する。`hash-only-source`は原本本文がGit未収録であることを示し、恒久保持や現在のpath実在を保証しない。`safe-xml-summary`も原本bytes同一ではない。小さな証拠JSON・XML要約・画像はGitへ保存し、全量TSV・ログ・archive・OpenAPI本体など約881MBの重複rawはdurable記録確定後の自隊cleanup対象とする。全量weights manifestも同じ扱い。本原稿時点では未削除で、最終cleanupの実行・停止/所有確認・撤去結果は別の証明に記録する。安全な抽出・assertの再現scriptはsupportの `package-durable-build-261028.py`。

文書採用時に、nightly artifact metadataは元のUTF16LE JSONからUTF8へdecode・再serializeした。また既存CRLFのtextはGit blobと同じLFへ揃えた。manifestのsourceBytes/sourceSha256は採取時の元原本、packagedSha256は文書コピーのGit blob bytesを指し、変換した行のmodeに明記した。build JSON/XMLの同梱SHAをGit indexでも再照合した。support原本や実測値は変更していない。

既存a8bから不変の新Kotlin2file末尾blank行により、PR全差分 `git diff --check b7e...f281` は警告を返す。作業treeの未commit差分が無いことと区別し、全差分チェック成功とは記録しない。目的外整形はしていない。

実機/探索は[実機パッケージ](../real/README.md)と[対象SHA付き検分記録](../review.md)へ対応付ける。最終e67 CI成功と実装mergeを別途API照合し、[採用記録](../implementation-adoption.json)と[完了JSON](../../CMP-261027-1646.json)へ対応付けた。raw cleanupはなお未完了で、最終撤去証明を別記する。
