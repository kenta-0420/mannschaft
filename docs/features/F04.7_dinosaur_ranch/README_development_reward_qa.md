# 隔離開発の正の報酬検証

この経路は正式な質問・素材・64 mappingを承認または公開しない。runtime の承認登録は引き続き空である。

中央条件は ranch-isolated profile と mannschaft.ranch.development-fixtures=true の両方。prod/production が混在したら最優先で拒否する。既定は OFF。DEV_ に続く大文字英数字・下線の reasonCode を保存 policy の provenance として予約する。公開 ACK と管理画面は六言語で開発用であることを明示する。本人 DTO の版は変更しない。

既存 SYSTEM_ADMIN/ACTIVE admission、公開 UI、policy Codec、実 worker、source ACK を利用する。新 endpoint、DDL、直接 SQL INSERT、第二 Spring は追加しない。enabled policy の配送五値は既存測定 bounds validator を必ず通す。試験用 TEST_ONLY_NOT_MEASURED 値は runtime 測定の証拠ではない。root による実測と固定 runtime helper の準備が必要で、ここでは worker を起動しない。

DEV 初回だけ現在 UTC 週の月曜 00:00 を許可する。control singleton lock 下で policy・reward budget・decision 全件と REWARD 種別 ledger が空であることを再検査する。既 CARE/PURCHASE 台帳は対象外で、control version 0 や未設定フラグを追加条件にしない。正式 mode は従来の将来週境界と正式 readiness を維持する。

DEV mode の consumer は成功 event/canonical replay の後、control→owner の順で同じ mutex を取得する。公開も同じ control lock を利用する。RR の先行 replay 読取後に古い policy snapshot を使わないよう DEV policy 選択だけ locking current read にする。Feed/Purchase は変更しない。DEV 直列化による throughput と bounds への影響は測定対象であり、性能合格を主張しない。

OFF mode では正準 DEV policy の新規 credit を公開 policy と既凍結 budget の両方で DEFER に保留し、配送設定は empty、本人の現行報酬 projection は disabled にする。既保存成功 ACK/decision replay、本人 ledger/history 自体は保持する。古い正式 policy への暗黙 fallback はしない。

試験候補は RanchDevelopmentPolicyIT（実 Bean/MySQL、合成資格と専用行の fixture）と RanchDevelopmentFixturePolicyGateTest（標準 Environment）。control 待機二件を最大5秒で実観測する並行 fixture は pool2 性能証明ではない。CARE/REWARD ledger 空条件の人工 fixture は通常育成や実 worker の成功証拠に数えない。自分の role/command/policy/Ranch/user 行だけ FK 順で回収する。

状態: 構文/source review 候補。actual red/green、build、DB、API、UI、worker、73AC、ALICE は未実行・未達。正式素材の gate は OFF のまま。

既LEASEDを受け取ったconsumerがDEV環境OFFを確認した場合もDEFERを返し、既orchestratorからsource.deferへ進む。源は現token/LEASED/期限のCASでattemptを一回復元してRETRYを保持し、budget/decision/ledgerは新規作成しない。Codec/hashや壊れたreason・凍結budget整合違反は従来例外のままで保留へ握り潰さない。実orchestrator＋reflection source TXの候補試験は合成transport前提を使い、native活動や性能の証明とは区別する。actual red/greenは未実行。
