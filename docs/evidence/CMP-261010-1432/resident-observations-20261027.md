# 交代相談の独立住民観測（2026-10-27）

固定 source head `0d5e51da25054e379afd5db51da6afca6cd1cd2e` の継続実行3で探索した3住民の観測を、既存の統合気づき箱へ追加する。実行出所、登録時に付与したID、目的と部分達成は [安全化summary](resident-observations-20261027.json) に保持する。原本はprivate参照とSHA-256で追跡し、画像実体、認証情報、ブラウザプロセス情報は公開しない。

3名とも `executionCompleted: true`、`goalsReached: false`。目的全体の達成、固定E2Eの成功、coverage合格、戦役完了を意味しない。D原本の `goalsReached` 配列は部分達成の列挙として保持し、boolean trueへ変換しない。

| 住民 | 達成した範囲 | 未達・未実証 |
|---|---|---|
| A | 指定理由の相談をUIで1回提出。中断復帰後に18時の自担当を再発見 | 相談状態・処理者の再発見。受諾や承認など他住民の結果は推論しない |
| B | マイシフトを離脱・再訪し、既存の08時・14時担当を確認 | 相談未発見・未受諾、新18時担当確認未達。summary API 403とUI操作や未発見との因果は未実証 |
| D | ダッシュボード承認cardから交換一覧へ自力到達。対象理由の相談と承認前18時A+Cを確認 | 承諾待ちのままで管理者承認未達。承認後担当比較とS3/X境界は未実施 |

6気づきはすべて `observed-unverified` / `trusted: false` とする。数値ID・日時表示の論点は既存 `CMP-261021-0650` と関連するが、新規CMP、blockers、製品修正へは昇格しない。

root固定追試V1は `existing-request-identity` でmutation前に失敗した。旧 `target_user_id` を確認したroot手順の誤りであり、正規保存は `recipient_mode=SPECIFIC`、`is_open_call=0`、`target_user_ids=[4]` だった。製品不具合とは扱わず、独立住民の目的達成へ転用しない。後続追試の結果は本観測と別に扱う。

累積正本は `docs/evidence/CMP-261015-1510/resident-overlay.json`。最新mainに存在する18 persona / 18 journey / 42 insightの全フィールドを保持し、今回3 / 3 / 6を追加して21 / 21 / 48とする。環境の既存値や他runの出所を今回のheadへ上書きしない。

静的検査は [結果JSON](board-validation-20261027.json) を参照。生成・overlay限定検査と不正10件拒否は合格。通常validatorおよび自己検証全体は、overlay検査前に既存GitHub snapshot参照集合不一致で失敗。WindowsでUI確認を試みたが、PowerShell/CIMの所有情報取得が7秒上限に達し、ボード描画へ到達せず失敗した。気づき箱の実画面・取込・リロード・重複なしは未確認であり、視覚確認の合格へ転用しない。

UI起動前には初版PowerShellランチャーのUTF-8 no-BOM文字コード解釈でもparse失敗があった。原失敗を保存し、UTF-8 BOMと事前構文検査で起動前の問題を修正したが、その後のCIM timeoutによりUI目的は未達だった。失敗後は新ブラウザを起動せず、専用profile完全一致とWindows開始世代を照合して撤収した。最終的に専用Chrome参照0、13901待受0、専用tmp削除、既知9世代不在を確認済み。原失敗と撤収のprivate証拠SHAは検査JSONへ保持する。
