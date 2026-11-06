# 第三陣 実機・採用証拠

## 現在の判定

| 対象 | 採用状態 | 根拠 |
|---|---|---|
| CMP-261002-0743 | 実装確定 | PR #4143 head 89f8a436354113d7e1affe101af0d06e86c10e68、通常merge 13b3698b1ac4d2c1ea0fa4491e0ab9847314ba7d、2026-10-27T02:05:57Z。FE40434525496・E2E40434525520・Gate40435673624のexact-head successを集約担当がGitHubで確認 |
| CMP-261006-0632 | 正式採用確定 | 旧PR #4145は不採用のまま、PR #4160 head903064839a1181d638259ff7d33ca6674c698768を全21check完了後に通常merge6011154897ad62d6281da749b994fc754773d42e（2026-10-27T04:52:59Z）。trusted at-merge eligible=true |

殿は対象コードのreview finding0、下記実機と独立3住民の実施を確認してreview/real/exploratory stagesをpassedと判定した。0632は実施stageに加えてPR4160で最終採用ゲートも満たした。2338も採用済みで、3件の台帳を一括完了草案へ更新した。

## 正式実機と残存リスク

実サーバーFE3003/API8083、source d3d6d05f942cf1fcb7a5a77352d1593092e1c30dで実施。route/API mockなし。最終結果は15-passed-on-manual-reruns-with-history（MOVED6・大会9）。製品・原spec・assert・expect10秒・時間枠を変更せず、各suiteを手動で1回再実施した。自動retry0、再実施分のskip/flaky/unexpected各0。初回から無失敗という結果ではない。

| suite | 初回 | 手動再実施1回 | 未解決の制約 |
|---|---|---|---|
| MOVED | slug欄前のHTTP200全体Nuxt errorで1失敗・5未実行 | 6成功、2026-10-27T00:54:56.159Z開始、131.809秒 | 初回原因未確定 |
| 大会 | 8成功・未認証のlogin遷移1timeout | 9成功、2026-10-27T01:04:58.766Z開始、132.882秒 | 初回原因未確定。後の成功でcold-route原因を説明したとはしない |

再実施15件のruntime eventはpageerror0/Nuxt payload error0。一方、/_nuxt/*のnet::ERR_ABORTEDは1099件、その他request failure0。通信失敗全体が0とはしない。未認証再実施のlogin request9097ms→response10363ms→document commit10395msはテスト開始基準で、HTTP実時間1266ms。

MOVEDは両scopeの実UI slug変更、旧URLのsubpath/query/hash保持と往復、非管理者の設定拒否、他テナント直URLで非公開情報なしを確認。競合応答の順序は実page unit red6→green15で検証した。大会は6言語でTOUR_022/422とTOUR_024/404の翻訳通知、失敗後成功通知0/checkbox0、会員・非所属の編集操作非表示、未認証login遷移を確認。canonical slugを使用し、numeric aliasの親組織404は成功扱いにしない。

## 実行sourceと再現性

preflight00:48:52Z/postflight01:08:32Zでsource・PID/startticks・artifact・specが一致した。frontend/app tree e762079de244564f4d54bd2969b344449d071dc4、backend tree f93385f15e46177a1815f2edc52b3ef670732d65。FE PID304777/start6796606、4409files、artifact digest 8d16b2d876a311bd049ddac00d1461778fcffaed1186e2054427b2f66dd69c26。BE PID308358/start6849288、jar SHA256 6c8a45cdfe05d9e27ad27d99ba939cbd14fd2712a78a33c1d941c9700eb2a2d4。manifest SHA256 149fb86f627d0f3be76be4db3ce980fd66fa95ecd56340b615bf3bf2fd0630aa。

| spec | raw CRLF SHA256 | Git LF SHA256 |
|---|---|---|
| scope-shell-moved.real.spec.ts | ff238b42e332894cd70d911c9b4f5df646ee4907c82b1bee13d7ef3a02452aaf | 7ed6a196f509e02db3795ed68a4e39fca895ef7a59fded7f5ac87eaeaf170ac2 |
| cmp0743-entry-errors.real.spec.ts | f3da554e6b9b2205e5d14b87bd64304b1d8713c77772692447ccebf396b510a8 | 7db3b812d8ec6333faa78376ec0c3fe96d31ef0cc21b26fcbab0c4320c453f41 |

[path別Git blob proof](source-correspondence.json)は0743採用headの14対象pathと、0632正式PR4160の4対象pathが実機sourceと同じことを固定SHAで再照合した。旧PR4145のコード対応履歴は別に保持し、採用証拠として使わない。

実機以後のmainにはprefetch3files85追加とdashboard/store4files211追加/24削除が入った（READMEは両群に共通）。Nuxt設定と共有画面/storeを含むため環境全体同一・最新mainでの再実機成功は主張しない。main5ffの住民記録追加はdocsのみ。中間source b8d3の大会9/MOVED6成功、次件source849の実機・探索は、この正式結果の代替にしない。

## 視覚証拠

殿と集約担当は以下の正式代表3枚を実画像で視認した。formal15枚全部を視認したとはしない。殿が旧中間sourceのMOVED6枚を視認した記録とは区別する。安全なtest fixtureのPNGだけを公開し、raw body・秘密値は転記しない。

- [組織owner](screenshots/organization-owner.png)
- [de modalの2種失敗通知](screenshots/tournament-de-errors.png)
- [未認証login到達](screenshots/anonymous-login.png)

## 回収とruntime寿命

実機・初回失敗・診断のself-fixtureは所有照合して全て204→404。探索fixtureもtemplate/affiliation/team/org/tournamentの5kind全て204→404、private exploration-fixture.jsonはremoved。初回helperはtournament.nameの誤照合で保留した（実DTOはcontent.name）。作成監査createdBy4/createdAt00:10:27Z、manifest00:10:23.893Z、org19/大会5、専用e2e DBを独立照合し回収した。自動承認の初回却下は証拠補強後に解消し、履歴をprivate cleanupHistoryに保持。R1専用アカウント言語は殿UI更新200→html ja/combobox日本語で復元確認、殿・住民全browserはCLOSED/exit0。回収blockerなし。

第三波FEは所有確認後SIGTERM・scratch removed、元manifest/履歴と完了済み結果を保持。第三波終了時点ではBE8083/PID308358/start6849288/BEtreef93385、DB13340等をowner rootが第四波実機・探索へ期限付き貸出した。その後、第四波終了時にFE/runner/BE・scratch・専用DB2コンテナ/volumeの回収を確認済み。現在の保留は次の0660に具体用途を持つJAR/cache（owner root、2026-10-27T23:59JSTまで）で、過去のBE貸出を稼働中と扱わない。第四波の回収根拠は同戦役のreal文書とprivate final-cleanup-receipt.json/retention-next-0660.json。他owner run-55fg9V/run-8BA1u4は第三波で未操作、第四波の自身check残0でも他owner1件は保全した。4143/4154の所有WT/branch回収はprivate root-shipped-cleanup-final.jsonに記録済み。

## 0632の早期マージincident

PR #4145 head0c5b1bed3be1d08379addf6dee3c382cc7b76817は通常--auto操作が即時マージとなり、2026-10-27T03:22:37Zにfa8a6c6657ec486faed88fe4649d4059eb8b522dへ入った（admin未使用）。当時FE/E2E/Lighthouseは進行中。trusted summarizeImplementationChecksはマージ時未完了を拒否するため、後のCI成功でもこの採用は完了証拠に使えない。ガードは変更しない。通常revertと同4filesの再適用を経てPR #4160 head903064839a1181d638259ff7d33ca6674c698768で修復した。全21check（14success/7skip）完了後の2026-10-27T04:52:59Zにmerge6011154897ad62d6281da749b994fc754773d42e、trusted at-merge eligible=true。旧4145を採用へ戻さず、新正式採用のみをJSONへ記録する。

private原本：final-real/summary.json、各attempt、residents/R1〜R3、root-repro（全て.claude/runtime/october-third-wave-20261026配下）。
