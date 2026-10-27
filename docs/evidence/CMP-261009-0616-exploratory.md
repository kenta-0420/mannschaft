# CMP-261009-0616 独立住民探索

AC9は固定実機後に3住民へ目的だけ渡し別contextで探索し、欠落を含む観測・root裁定・気づき箱を分離する条件。目的成功へ条件を置き換えず未達を保持する。

公開正本は[観測](CMP-261010-1432/resident-observations-20261027.md)、[安全summary](CMP-261010-1432/resident-observations-20261027.json)、[気づき箱検査](CMP-261010-1432/board-validation-20261027.json)。PR4171 head6b988a712a401161822ae1945cd085844b57797e / merge6330937cbb54d3aad200104c285363f29ffb97b9に採用済。source0d5e、run CMP1432A-I03-cmp0649-4c89611f1c78。

| 住民 | 部分達成 | 未達・未実証 |
|---|---|---|
| A / ADHD傾向×中断・放棄 | 指定理由の相談をUIで1回提出、中断後18時自担当を再発見 | 相談状態/処理者の再発見は未達 |
| B / 高齢・低リテラシー×素直 | 離脱・再訪で既存08時/14時担当を確認 | 相談発見・受諾・新18時担当確認未達。summary API403との因果未実証 |
| D / 一般×隙間狙い | 自力でdashboard承認card→交換一覧、相談と承認前A+Cを確認 | B未受諾により承認未実施。承認後状態/担当/C保持比較と余裕時S3/Xは未実施 |

全3名executionCompleted=true / goalsReached=false。D rawのgoalsReached配列は部分達成でboolean trueではない。目的のみ・各自context・同actor並列loginなし。context閉鎖後root固定追試を分離し、起動前失敗やroot追試を住民成功に加算しない。

private原本resident-root-adjudication-i03.json / resident-purpose-contract.jsonと各住民rawの出所/SHAは公開summaryで追跡する。A/B未発見を確定不具合へ昇格せず、0616 scope外の修正を行っていない。

気づき箱の既存18 persona/18 journey/42 insightを保持し今回3/3/6を追加して21/21/48。observed-unverified / trusted=false。生成・限定negative10件拒否PASS、通常snapshot validatorは既存参照集合不一致FAIL。UIはCIM timeoutで描画未到達、視覚・取込・reload・重複なし未確認。専用profile/process/port撤収のみ確認済。探索実施passedは目的達成・全coverage・ボードUI合格ではない。

