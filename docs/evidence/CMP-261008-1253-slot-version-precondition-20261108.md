> 最新の追加証拠は [R24部分証拠](CMP-261008-1253/r24-partial-evidence.md)。以下のR20実機1194等は当時の履歴である。R24 source `f93884c6a43ae9be5973cf005dfe52ebef7d1262` と新integration `de0cdda46ff29c304efd9279d07e0d0dcf722187` を区別し、新headのfresh CIは未取得。

# CMP-261008-1253 — slot version前提修正の部分証拠（部分採択）

対象は #4308 の前提となる最小FE修正。初回slotVersion=0を有効値として送信し、確定応答を次操作へ反映する範囲に限る。全CMP-261008-1253・P2の完了宣言、正式完了JSON、Issue closeではない。R20の正規3runを含む部分証拠として採択された。出荷・mergeの状態は別管理である。

## sourceと受入範囲

[PR #4361](https://github.com/kenta-0420/mannschaft/pull/4361) は実測時点でDraft・未merge。実装head `119448370946a49cc8544c911068f53b189b3f0b`、tree `108e786369d0c435d325a34e8ed59c1a65f0f04c`、base `6cd103c824684a306a32cb4adfe7574be86dc35d`。製品5blobは旧ab6と同一。BE/API/DDL・認可変更を含めない。台帳は「P2 実装中」と旧PR #3724を維持する。

| 条件 | 実装と製品単体20case（権限は別確認） | R20の部分実機 |
|---|---|---|
| 初回0を有効値として必須送信。不正・欠落版は書込拒否 | [frontend/app/composables/useShiftBoard.ts:25](https://github.com/kenta-0420/mannschaft/blob/119448370946a49cc8544c911068f53b189b3f0b/frontend/app/composables/useShiftBoard.ts#L25)、[frontend/app/composables/useShiftBoardVersion.spec.ts:28](https://github.com/kenta-0420/mannschaft/blob/119448370946a49cc8544c911068f53b189b3f0b/frontend/app/composables/useShiftBoardVersion.spec.ts#L28)・92。必須slotVersion型は[frontend/app/composables/shift/useShiftAssignmentApi.ts:8](https://github.com/kenta-0420/mannschaft/blob/119448370946a49cc8544c911068f53b189b3f0b/frontend/app/composables/shift/useShiftAssignmentApi.ts#L8) | 初回requestVersion 0からPATCH200、確定版1 |
| 確定DTO全体・版を反映し追加・解除へ渡す | 同useShiftBoard.ts:33/48/70、spec:28 | PATCH 0→1→2→3→4→5、割当配列[3]→[3,6]→[3]→[3,6]→[3]。実機resultだけでfull DTO全字段保持まで証明しない |
| 移動両版・第2PATCH失敗時の第1成功と元エラー保持 | 同useShiftBoard.ts:53、spec:53/66/79 | 自然UIのslotmove導線なし。第2PATCH途中失敗はunitのみ |
| stale409で再送せず既存状態・元エラー保持 | 同useShiftBoard.ts:48、spec:110 | requestVersion4→409、日本語通知・再送なし。実helperguardとreceipt対応 |
| 遅いGET/PATCHの巻戻り・別schedule混入を防ぐ | 同useShiftBoard.ts:14/33/78、spec:121/135/149/165/183 | 応答競合全組合せはunit範囲 |
| 権限境界を維持 | [frontend/app/pages/teams/[slug]/shifts/[scheduleId]/board.vue:307](https://github.com/kenta-0420/mannschaft/blob/119448370946a49cc8544c911068f53b189b3f0b/frontend/app/pages/teams/%5Bslug%5D/shifts/%5BscheduleId%5D/board.vue#L307)。認可ロジック変更なし。製品unit20に権限試験があるとはせず、3role実機で別確認 | ADMINのDRAFT一覧・詳細可。MEMBER/foreignadminは対象list非表示、detailとslots404。拒否board空表示は既存制約 |

## 製品red/greenとCI

正本のred来歴は18・19・20case各世代で選択assertion1fail、残りpending。全20caseが同時にredだったとはしない。greenは各18/19/20全pass。rootの回収済みprovenance review（SHA `fce5c9ea8c04994a1058c6fc7cd0e93b73c1aeafc4bd13c9f93aa22db4d8ab97`）を内部出典とし、原stderrは転載しない。

現sourceの[FE CI 42437278525](https://github.com/kenta-0420/mannschaft/actions/runs/42437278525)はlint/typecheck/Vitest成功、[E2E CI 42437278484](https://github.com/kenta-0420/mannschaft/actions/runs/42437278484)成功。22checkは15success/7skip（root確認）。[BE CI 42437278572](https://github.com/kenta-0420/mannschaft/actions/runs/42437278572)の実shard等はskipで、BE実test成功へ数えない。技術helper・fake境界の件数を製品20caseへ加算しない。

## R20実機の到達事実と部分採択

実終了exit0、全50UI応答、A013/B021/C016のdone応答と3 actor finalをroot受領。coded mainflow16枚・resident10枚をroot実閲覧。元receiptのvisuallyChecked:falseは変更せず、別root採択記録で実閲覧を扱う。初期entry26228ms、row615ms、count1。最後に固定read-only GET200による確認あり、extraPageClosed。apiExceptions:[]は対象割当操作のAPI代行例外がない意味であり、fixture正常API・ログイン・固定observer GETまでAPI0とは表記しない。

最終照合では9原本の全hash、所有68世代の終了、35stageのclose/stop、限定Docker照合とscratch不在、canonical exit0・cleanup removed・errorfalseを確認した。final.mainflowResultとsnapshotの構造、および全3runのreport/context/trusted=false/classificationが住民送信内容と一致し、正規3runを部分証拠へ採用した。

採択記録 `root-r20-final-partial-adoption.json` SHA `4227240396d83e722a615ff5ef35dae536c321968c1a9780c42881759d8d752e`、最終監査SHA `47f34b9641f7129594577f7755f18f0c80e9b6ae1c2d3732645022a6bb78cf5f`、終了原本copy-manifest SHA `4e2779dd3c0f82de827d1f5e1af3e3709236fdf0626d67fa25d85739079cbbe4`。全CMP1253完了とはしない。

| fresh住民 | 受領した観測 | 区別・限界 |
|---|---|---|
| A | 目的true、dialog内割当・再表示後残存、画像3 | 画面範囲の報告。正規原本照合済み・部分証拠へ採用 |
| B | 目的false、自team入口不明・一覧空・詳細notfound・board空・2026年12月myshift空、画像4 | 探索実施と目的達成を同一視しない |
| C | 画面範囲true、foreignlist権限案内・detailnotfound・board空・変更依頼フォーム3種表示、画像3 | PDF出力未確認、入力送信0。全面的達成ではない |

住民観測は正規原本として採用後もtrusted=false/observed-unverifiedを保持する。目的達成・探索実施・rootの裏取りを区別し、報告だけから未確認bug/coverageへ昇格しない。B/Cは今回404後capture本文空の範囲であり、全時点・全経路のデータ非露出は未検証。住民phaseは中継を含み約35.5分で、性能/SLA合格の主張ではない。全体TTLの延長は行っていない。

## 履歴と未検証

旧R16/R17中断を完了へ昇格しない。R17はroot端末の長文done解析失敗、今回は2048byte guardと本人作成の短doneを使用。R18初期行失敗は原因未分類。R19はWAIT_VISIBLEのTimeoutError・10007msを直接観測。R20のmounted＋hydration-settled境界後成功を根拠に、旧失敗の根因を遡及確定しない。

Long統一・2^31境界・note-onlyのBE契約・DB/履歴不変など全CMP原ACは今回完了化していない。JS safeIntegerの範囲とJava PATCH Integer.MAX_VALUEの差は残る。slotmove自然UI、第2PATCH途中失敗の実機、mobileSafari、loadはNOT_RUN。正規3runは採用済みだが、B0累積への統合は別途とする。現正本の累積件数は本書で断定せず、旧字段を保持して統合する。global validator既知failは合格へ変えない。

[docs/development/completion-evidence.md](https://github.com/kenta-0420/mannschaft/blob/119448370946a49cc8544c911068f53b189b3f0b/docs/development/completion-evidence.md) の完了ゲートを満たした宣言ではない。新規Markdownの追記と台帳evidenceセルの追加だけを提案し、7列・元文・旧PR・状態を維持する。他CMPや他scopeを完了しない。
