# P9 予約・シフト E2E テスト法案

> 対象: F03.4 / F03.5（当初除外→マスター指示で最終確認に追加）
> 凡例・テスト層は [README](./README.md) 参照。実ファイル裏取り済み（品質 ◎）。
> ※ 実機テストは別エージェントが並行実施中。本書は設計-実装トレーサビリティの最終確認。

---

## 1. トレーサビリティ監査サマリ

### F03.4 予約管理（`docs/features/F03.4_reservation.md`）— 99% 実装
| 機能要素 | 実装(開いたファイル) | 判定 |
|---|---|---|
| 予約ライン管理(最大5本) | ReservationLineEntity/Controller / admin/reservation-settings.vue(Tab2) | 🟢 |
| 予約枠(30分単位・単発/繰り返し) | ReservationSlotEntity/Controller / teams/[slug]/reservations.vue(Tab0) | 🟢 |
| 営業時間/ブロック時間 | ReservationBusinessHour/BlockedTime Entity/Controller / admin/reservation-settings.vue | 🟢 |
| 自動確定/手動承認モード | ReservationEntity.status/ApprovalMode/confirmReservation() | 🟢 |
| リマインド(1日前/1時間前) | ReservationReminderEntity/Service/バッチ | 🟢 |
| 統計(稼働率・ノーショー率) | ReservationService.getStats()/StatsResponse | 🟢 |
| 来店完了・ノーショー・キャンセル・リスケ | ReservationService 各メソッド / ReservationList | 🟢 |
| 休業一斉通知 | EmergencyClosureService/Entity/Controller(V3.066-069) / admin緊急休業タブ | 🟢 |
| **予約確定時のチャット自動開設** | `chat_channel_id`列・`ReservationConfirmedEvent`・`ReservationChatListener` **全て無し** | 🔴(V13.032「Phase13.1.2以降」=🔵) |
| 月間カレンダーUI/予約拒否API/bulk予約枠 | 設計に🔵マーク | 🟡 未実装 |

### F03.5 シフト管理（`docs/features/F03.5_shift/README.md`）— 100% 実装(v2 Phase1-3)
| 機能要素 | 実装 | 判定 |
|---|---|---|
| スケジュール(DRAFT/COLLECTING/ADJUSTING/PUBLISHED) | ShiftScheduleEntity/Service/Controller | 🟢 |
| 希望提出(5段階強度 PREFERRED〜ABSOLUTE_REST) | ShiftAvailabilityEntity / my/shift-request.vue | 🟢 |
| 自動割当(スコアリング貪欲法) | ShiftAutoAssignService/ShiftAssignmentRun | 🟢 |
| 勤務制約(月次上限・連続勤務) | MemberWorkConstraintEntity | 🟢 |
| D&D編集UI/目視確認必須(AUDIT_LOG) | ShiftSlotService/ShiftBoard(vuedraggable) | 🟢 |
| 変更依頼3パターン(A-1確定前/A-2指名/A-3オープンコール) | ShiftChangeRequest/ShiftSwapRequest Entity | 🟢 |
| PDF出力/リマインド(48h/24h前)/前週コピー/自動アーカイブ/兼務重複チェック | 各Service | 🟢 |

---

## 2. 既知疑義の裏取り結果（原典逐語で判定・思い込みを排す）
- **「二重実装」疑義 = 誤り（二層構造が設計意図）**: `EmergencyClosureService`(予約ドメインロジック=キャンセル+履歴) と `confirmable_notification_*`(F04.3 汎用通知配信基盤) は**関心の分離**。設計違反ではない。
- **予約チャット自動開設 = 🔴真の未実装**: `chat_channel_id`列(V3.062になし)・`ReservationConfirmedEvent`なし・`ReservationChatListener`なし。V13.032 に「Phase 13.1.2 以降で F04.2 連携」明記=🔵将来正規。
- **リアルタイム既読**: 予約固有の既読機能は設計されておらず F04.2/F04.3 管轄。現状 `GET .../recipients` の `is_confirmed` を poll。WS は統合時。
- **管理ダッシュボード導線 = サイドバー非統合・専用URL(設計通り)**: `/admin/reservation-settings`(枠/営業時間/ブロック/緊急休業) と `/teams/{slug}/reservations`(一覧/ライン)。

---

## 3. E2E 実機シナリオ（代表・トレーサ付き）
- **[F03.4-E01]** 自動確定: 会員が空き枠→ライン選択→予約→approval_mode=AUTO で即 CONFIRMED、booked_count アトミック+1、リマインド2件自動生成、RESERVATION_CONFIRMED通知。（§5.1727-1753）
- **[F03.4-E02]** 手動承認: PENDING(pending_expires_at=+48h)→ADMIN承認→CONFIRMED。**チャット自動開設は🔴未実装→Phase13.1.2で再検証。**（§5.1748）
- **[F03.4-E03]** 休業一斉通知: blocked-time設定→影響枠 CLOSED→該当 PENDING/CONFIRMED を SYSTEM キャンセル→予約者へ通知→`emergency_closure_confirmations.is_confirmed`で既読追跡(poll)。（§5.1769）
- **[F03.5-E04]** シフト発令: DRAFT→COLLECTING(希望提出5段階)→ADJUSTING(締切自動遷移)→自動割当(ABSOLUTE_RESTブロック/WEAK_REST警告)→D&D修正→兼務重複警告→目視確認ボタン→PUBLISHED→全員通知→ダッシュボード反映。（§5.8-49）★最重点
- **[F03.5-E05]** 交代: A-2指名(相手通知→承認で入替) / A-3オープンコール(全体broadcast・opt-out尊重→先着claim→起案者select)。（§5.140-158）

### 重点（実機優先度）
- 🔴 予約状態遷移(AUTO/MANUAL の PENDING→CONFIRMED→COMPLETED/CANCELLED 網羅)
- 🔴 **予約枠 booked_count アトミック更新**(2件同時申込→1件のみ成功、もう1件 409＝オーバーブッキング防止)
- 🔴 シフト自動割当+制約(ABSOLUTE_REST で割当不可・WEAK_REST は警告のみ)
- 🟡 営業時間変更時の既存枠カスケード(closed_reason='BUSINESS_HOURS')、緊急休業一斉キャンセル+既読、兼務重複検出

---

## 4. このフェーズの「設計にあるが UI/導線が無い」確定
| 機能 | 状態 |
|---|---|
| F03.4 予約チャット自動開設(+初期メッセージ+SUPPORTER投稿権限例外) | 🔴/🔵 Phase13.1.2(F04.2連携) |
| F03.4 月間カレンダーUI(monthly-summary) | 🟡 Phase3後半(設計🔵マーク) |
| F03.4 予約拒否API(reject)/bulk予約枠作成 | 🟡 Phase2/3(設計🔵マーク) |

## 5. 既存 E2E spec ギャップ
- 予約: reservations.spec.ts は表示テストのみ→会員作成→承認→リマインドの一気通貫が必要(#1597 で会員作成契約は根治済)。
- リアルタイム既読WS は F04.2/F04.3 統合時に実装・検証。
