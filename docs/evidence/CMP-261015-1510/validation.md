# CMP-261015-1510 登録資料の検証

## 累積住民overlayの回帰

今回統合するvalidator修正は元commit `5e86c0d018688cf7787ee743510a42c92da7bffe`、登録branchへのcherry-pick `870f039`。修正元workerのbaseは `092ca37a587f6b589df9234126d6f43916c036cc`。アプリ実機c717・最終製品0042とは別の変更である。

旧validatorは全journeyのsourceHeadをoverlay全体の単一sourceへ一致させていたため、第一陣03beの3persona/3journey/5insightと、第二陣c717の3/3/8を合わせた累積6/6/13を拒否した。実行REDはexit 1、エラーは「住民journeyの宣言・目的・runId・source・証拠が不整合です」。

修正ではこのglobal一致条件だけを除去し、persona/journey単位のhead・run一致、insight原runとjourneyの一致、未検証stateとtrusted=falseの条件を維持した。rootが2ファイル差分を検分し未解決findingなし。同じCodexから検分者Codexを追加起動していない。

原本 `agent-october-login-1510-20261026/docs/prototypes/.b0-local/provenance-proof/` のsummary・ログを参照し、累積overlayのself-test exit 0、通常validator exit 0、不正overlay 10例拒否を確認した。元生成JSへ復元後の通常validatorもexit 0。詳細は [非秘密の回帰集計](validator-regression-summary.json)。

登録資料の日本語をUTF-8で修復し、既存CMP-261022-0338の3persona/3journey/5insightは全フィールド一致で保持する。新8件はいずれもobserved-unverifiedで、確定blocker・CMPは増やさない。生成・通常validator・self-test結果は下記に記録し、マージ後に完成証拠と再照合する。

## 完了登録の確定

実装PRはhead0042、2026-10-26T11:01:44Z、merge bd573ee5c26c341d5d55cd11ad500b74977b945dでマージ済み。rootは製品差分、正式20件、3住民、CI、旧5件の保持と新8件の来歴を独立検分し非CI部分を合格とした。全stageをpassedに更新し、台帳の既存1行を完了へ変更する。完了登録の最終公開差分はrootが別途検分し、完了登録PRを通常マージする。

## 最終0042 CI反映（2026-10-26）

rootの独立確認済みci-0042-summary.jsonを[CI記録](ci-summary.json)として保存し、証拠JSONのci配列へexact0042に対応する成功11runを登録した。旧Gate cancelledは収録しない。Backend shard/ArchUnit等diff-skipは試験成功として数えない。

mainのPR #4138による台帳1行追加で再BEHINDとなり通常マージが拒否された後、ユーザーがPR #4133/head0042への `--admin` を明示許可した。rootはhead・CI・MERGEABLE・先行差分を再確認してadminマージし、MERGED・merge SHA・時刻とmain反映を独立確認した。この許可は実装PR限定で、完了登録PRは通常マージを使用する。

## 最終生成と完了登録の検証

正本の参照commitは `bd573ee5c26c341d5d55cd11ad500b74977b945d`（実装マージ済みmain）。このcommitの台帳へ本登録の対象1行だけを完了化した。標準GitHub同期は2026-10-26T11:04:52+00:00に成功し、参照665件。スナップショット更新後に標準生成した結果は機能44、能力95、CORE25、非CORE19、blocker23、CMP597。並行PR #4138の台帳追加を含むため、登録前のCMP596から増えている。

10月行はIDが `CMP-2610` で始まる267件。状態が「完了」で始まらない機械的未完了は177件で、ここには重複3件・対応不要1件を含む。それら4件を除いた未完了候補は173件。「検証済み」「設計完了」などを勝手に戦役完了へ読み替えず含めており、10月全件が完了したという意味ではない。

- 通常validator成功、overlay自己検証成功。不正10例拒否・異なるhead/runの正規受理を確認。
- 旧3persona/3journey/5insight全値保持、累積6/6/13と正本・ignored copy・生成データの構造一致を確認。
- 匿名PNG3枚のSHA256一致と公開ファイル数3を確認。住民private画像実体・巨大raw・資格情報を追加していない。
- デスクトップなどへのコピー指定はないため、コピーとバックアップ作成は行っていない。

初回のcompletion unitは未commit参照とpending草案により20/21だった。資料commit後の再検証は21件すべて成功（fail/skip 0）。対象validateCompletionChangesはorigin/mainの台帳と完成JSONを比較し、Git HEADのcat-fileによる全参照存在確認も含めて成功した。

全履歴CLIは今回対象外の過去証拠を全走査するため、root判断に従って自分のPID・開始時刻・親・commandを照合して停止した（exit 1）。これは未合格であり成功として数えない。今回のゲートは21件unit・対象validateCompletionChanges・実装headのtrusted CIを採用する。公開変更19ファイルの未知の連続疑問符・置換文字・既知の資格情報形式は検出0、Markdown全ローカルリンク、PNG3枚hash、git diff --checkも成功した。
