# P8 スケジュール・出欠・安否・アンケート E2E テスト法案

> 対象: F03.1 / F03.6 / F05.4（当初除外→マスター指示で最終確認に追加）
> 凡例・テスト層は [README](./README.md) 参照。実ファイル裏取り済み（品質 ◎）。
> ※ 実機テストは別エージェントが並行実施中。本書は設計-実装トレーサビリティの最終確認。

---

## 1. トレーサビリティ監査サマリ（実装率 95%超）

### F03.1 スケジュール・出欠（`docs/features/F03.1_schedule_shared.md`）
| 機能要素 | 設計根拠 | 実装 | 判定 |
|---|---|---|---|
| 繰り返しスケジュール(recurrence_rule JSON) | §3.104-122 | ScheduleEntity/Repository | 🟢 |
| 「この回/以降/全回」編集(updateScope) | §4.492 | UpdateScheduleRequest.updateScope | 🟢 |
| 出欠確認(attendance_required)/集計(attendance_summary) | §3.73,153/§4.690 | ScheduleAttendanceService | 🟢 |
| リマインダー(ABSOLUTE remind_at / RELATIVE remind_before_minutes) | §3.252,268(V72.002) | ScheduleReminderService | 🟢 |
| クロスチーム招待(schedule_cross_refs) | §3.280-311 | ScheduleCrossRefService | 🟢 |
| 出欠アンケート(event_surveys) | §3.198-221 | EventSurveyEntity | 🟢 |
| **予約出欠募集(F55, task_type=ATTENDANCE)** | §3.327 | ScheduleScheduledTaskBatchService.materializeOne | 🟢 |

### F03.6 緊急安否確認（`docs/features/F03.6_safety_check.md`）
| 機能要素 | 実装 | 判定 |
|---|---|---|
| 安否実行/ACTIVE-CLOSED/一斉通知(push+WS ブロッキングモーダル) | SafetyCheckController/SafetyCheckPublishedEvent | 🟢 |
| GPS位置(DECIMAL10,7)/プリセット例文/自動掲示板スレッド | SafetyResponseEntity/MessagePreset/bulletin_thread_id | 🟢 |
| リマインドバッチ/要支援フォローアップ(NEED_SUPPORT→followups) | SafetyCheckReminderBatchService/FollowupService | 🟢 |

### F05.4 アンケート・投票（`docs/features/F05.4_survey_vote.md`）
| 機能要素 | 設計根拠 | 実装 | 判定 |
|---|---|---|---|
| 作成(DRAFT)→PUBLISHED→CLOSED | §3.69 | SurveyController/SurveyStatus | 🟢 |
| **PUBLISHED後 編集不可** | §3.99「PUBLISHED後は設問の追加・変更・削除不可」 | updateSurvey で status!=DRAFT は **409** | 🟢 |
| 配信ALL/TARGETED/匿名/設問4種/results_visibility/期限自動CLOSE | §3 | 各実装 | 🟢 |
| 回答送信/集計(グラフ)/CSV(BOM)/結果閲覧者権限 | §4 | SurveyResponse/Result/Export | 🟢 |
| **予約アンケート(F55, task_type=SURVEY)** | §3.327/§4.20 | BatchService→createSurvey+publishSurvey | 🟢 |

---

## 2. 一気通貫シナリオ（組織→複数チーム→個人ダッシュボード→回答）★当初の懸念領域
**[P8-一気通貫-01]**
1. 組織管理者が組織スコープでアンケート作成(`POST /organizations/{orgId}/surveys`, distribution_mode=ALL)
2. 公開→target_count=組織全メンバー(スナップショット)、`SURVEY_CREATED`通知配信。**通知に組織名が出る**(NameResolverService.resolveScopeName)
3. 複数チーム所属メンバーも「組織→アンケート」として個人ダッシュボードで受信(スコープ境界)
4. メンバーが個人ダッシュボードから回答(`POST /surveys/{id}/responses`)。表示は「組織名 - タイトル」(scope情報付き)
5. 公開後の設問編集試行→**409**(§99-101)
（トレース: F05.4 §2.37/§4.484/§3.99, F03.1 スコープ§3.16-26）

---

## 3. 既知疑義の裏取り結果（思い込みを排した確定）
- **F55 自動生成 = 🟢実装**: `ScheduleScheduledTaskBatchService` `@Scheduled(fixedDelay=60_000)`・`REQUIRES_NEW`、SURVEY→createSurvey+publishSurvey / ATTENDANCE→openAttendanceSolicitation。MAX_ATTEMPTS(5)超で FAILED。
- **「組織で修正したら反映されるか」= 公開後編集不可が正しい設計**: アンケートは PUBLISHED 後・安否は実行後、編集概念なし(スナップショット)。「修正の伝播」ではなく「公開前に確定」が仕様。
- **スコープ境界 = 🟢一気通貫確認**: org/team/personal の各API・ページ存在、scope名解決で組織名表示。

---

## 4. このフェーズの「設計にあるが UI/導線が無い」確定（🔵 先送り正規）
| 機能 | 状態 |
|---|---|
| F03.6 ログイン直後の未回答安否ゲートモーダル(`GET /safety-checks/pending`) | 🔵 Phase4未着工(現状 `?status=ACTIVE`で代用) |
| F03.6/F05.4 マイページ回答履歴タブ(`/safety-checks/my`) | 🔵 Phase4(当面非表示) |
| F05.4 アンケート→ブログ草稿生成(`/generate-blog-draft`) | 🔵 Phase2未実装(F06.1連携) |
| スケジュール複製の「この回/以降/全回」選択 | 🟡 単発複製のみ(繰り返し複製は機能55第四陣検討中) |

---

## 5. E2E 実機シナリオ（代表・トレーサ付き）
- **[F03.1-E01]** 繰り返し「この回のみ変更」(updateScope=THIS_ONLY→is_exception=true、他の回・parentは不変)。（§4.492）
- **[F03.1-E02]** 出欠集計+リマインダー(ABSOLUTE 29日09:00 / RELATIVE 開始60分前)自動送信、{attending/partial/absent/undecided}集計。（§3.244-277）
- **[F03.6-E01]** 安否実行(ORG・40名)→push+WSブロッキング→SAFE+GPS / NEED_SUPPORT→自動followup→ADMIN がフォローアップ IN_PROGRESS。（§4.288-337/491-543）
- **[F05.4-E01]** アンケート作成(DRAFT)→公開(target_count=25)→**公開後編集で409**→回答→CLOSE→集計。（§3.99/§4）★最重点
- **[F05.4-E02]** 予約アンケート(F55): scheduled_surveys 指定→scheduled_at到来でBatch materialize→PUBLISHED自動生成→回答。（§4.565-574）
- **[F03.1-E03]** クロスチーム招待(PENDING→ACCEPTED、相手にミラースケジュール、出欠は独立)。（§3.280-311）

## 6. 既存 E2E spec ギャップ
- F03.1/F03.6/F05.4 は実装充実。MUST: F05.4-E01(公開後編集不可)・F03.6-E01(NEED_SUPPORT自動followup)・F55 materialize(E02)。
