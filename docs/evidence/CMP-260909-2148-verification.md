# CMP-260909-2148 割当正本の同期

サマリーを含む現在状態の読み出しは PR #3175 で `shift_slots.assigned_user_ids` へ移行済みだった。本件に残っていたのは、自動確定の書き戻しが全 `CONFIRMED` 履歴から JSON を再構築する処理である。現在 JSON を先頭に保持し、今回選択された `CONFIRMED` 担当だけを一意に追加する。履歴・手動記録・認可・TX・ロック・version・DB schema・予算 listener は変更しない。

## 受け入れ条件と先行試練

`ShiftAutoAssignCurrentSourceContractIT` の実 Service・実 MySQL 試験。テスト TX で所有 fixture を作り、保存後は flush/clear して再読する。通知配送は観測しない。

| AC | 検査 | red | green |
|---|---|---|---|
| 1 | PROPOSED A/B → 正規の手動差分割当 A → Bだけ確定でA/Bを保持 | A消失 | 成功 |
| 2 | 履歴なしJSON-only Aを保持 | A消失 | 成功 |
| 3 | 現在JSONにいない古いCONFIRMED Cを復活させない | A消失・C復活 | 成功 |
| 4 | 選択REVOKEDは追加せず、現在担当を保持 | 現在担当が空へ | 成功 |
| 4 | 内部emptyは更新せず、反復確定で重複を作らない（2件） | 既存成功 | 成功 |
| 5 | 既存JSON順序を先に保ち、今回担当を一意に追加 | 先頭の既存担当消失 | 成功 |
| 6 | サマリーが履歴でなく現在JSONを数える | 既存成功 | 成功 |

HTTPのemptyは DTO の `@Size(min=1)` を維持し、公開APIの仕様を変えていない。

先行redは [red-results](CMP-260909-2148/red-results.json) に担当者記号による5保存値差分とdigestを保存した。8件中5失敗・3成功・error/skip 0。修正後は同じ試練blobで新8件と関連127件、合計135件が成功し、failure/error/skip 0、Gradle exit 0。対象7クラスとdigestは [green-results](CMP-260909-2148/green-results.json) に記録した。両runの検証コピーは自然終了後に `cleanup=removed` を確認した。

## 全読み出しの走査

製品ソース全体から `ShiftAssignmentRepository`、`shift_assignments`、`assigned_user_ids`、`getAssignedUserIds` を走査し、呼出元と変換先を確認した。

| 現在状態の利用経路 | 参照元 |
|---|---|
| 枠・ボード用レスポンス | `ShiftSlotService#toSlotResponse` が枠JSONを復元 |
| 自分のシフト | `ShiftMyService#getMyConfirmedSlots` → `ShiftSlotRepository#findAllAssignedToUser` の `JSON_CONTAINS` |
| 個人ダッシュボードの今後の予定 | `ShiftMyService#getUpcomingAssignedSlots` → `ShiftSlotRepository#findUpcomingAssignedByUserIdBetween` の `JSON_CONTAINS` |
| 充足サマリー | `ShiftScheduleService#getScheduleSummary` が枠JSONの要素数を集計 |
| PDF | `ShiftPdfService` が枠JSON由来のDTO担当リストを利用 |
| タスク生成 | `ShiftToTaskService` が枠JSONを復元 |
| 人件費計上 | `ShiftBudgetConsumptionRecordListener` が公開対象枠JSONを復元 |
| 交代の依頼資格・承認差替 | `ShiftSwapService` が枠JSONを確認し、承認は `ShiftSlotService#replaceAssignmentForSwap` で同JSONと履歴を更新 |
| 自動確定の書き戻し（今回修正） | `ShiftAutoAssignService#updateSlotAssignedUsers` が現在枠JSON＋今回選択されたCONFIRMED担当を利用 |

`ShiftAssignmentRepository` の利用は自動提案・実行詳細・履歴更新・親／枠削除の連鎖に限られる。現在状態問い合わせ用の旧3クエリは削除済み。自動確定で全履歴から現在状態を組み直す残存経路を今回除去した。

My／Dashboard／サマリー／手動履歴は既存 `ShiftManualAssignmentSourceContractIT` 12件で実MySQLを確認し、人件費のJSON読取は無変更の `ShiftBudgetPublishedEventListenerTest` 10件を再実行した。PDF・タスク生成・交代の製品実装は読取監査の対象であり、今回この3経路の実機完了を主張しない。JSONの重複入力を他の更新APIで禁止する仕様変更も行わない。

## 残る検証

全CI・最終検分・権限あり／なし／他テナントでの実機・住民探索は未完了。ローカルの選択実行では ArchUnit 2タスクを除外したが、正式CIの必須検査は変更していない。完了台帳への遷移は、殿によるこれらの確認後に行う。
