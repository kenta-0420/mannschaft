-- Phase 5-D（最終局面・完結＝キャンペーン全廃）: クロスドメインFK撤廃 — 「RESTRICT → proxy_input_records」2件を撤廃only（孤児保持）。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーンの最終 PR。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」原則に従い、
-- 各ドメインの実テーブルが他ドメインを参照する FK を撤廃してきた。
-- 第一〜四陣（user/team/org 親 CASCADE・群1 users 親 SET NULL・群2 他テーブル SET NULL）を全廃し、
-- 最終局面 5-A（V114.001）で「発火不能群」12件、5-B（V115.001）で「RESTRICT → org/team 以外の他ドメイン実テーブル」11件、
-- 5-C（V117.001）で「RESTRICT → users 監査/作成者/操作者列」25件を撤廃済。
--
-- 本陣（最終局面 5-D・完結）が対象とするのは、baseline に残った最後の 2 件である:
--  ・schedule_attendances / fk_sa_proxy_record（proxy_input_record_id → proxy_input_records）
--  ・survey_responses     / fk_sr_proxy_record（proxy_input_record_id → proxy_input_records）
-- いずれも proxy_input_records（proxy ドメイン）を参照する別系統のクロスドメイン RESTRICT FK である。
-- ★この 2 件の撤廃をもって baseline は空（FK 0 件）となり、クロスドメイン FK が全廃される（キャンペーン完結）。
--    baseline 推移: 158 → … → 75 → 27（5-B 後）→ 2（5-C 後）→ 0（本 PR）。
--
-- ━━━ なぜ「撤廃only・孤児保持・リスナー/データ操作/NULL化なし」が安全なのか ━━━
-- proxy_input_records（代理入力ログ）は運用上「物理削除される」（保持期限ジョブ・退会 purge）。
-- 一方、参照元 schedule_attendances.proxy_input_record_id / survey_responses.proxy_input_record_id は
-- F14.1（代理入力・非デジタル住民対応）で「集計分離のための監査列」として追加された列で、
-- write-only / 不活性である（Entity の @Column マッピングはあるが、getter ベースの逆引き finder・JPQL・JOIN は 0 件）。
-- したがって proxy_input_records が物理削除されて当該列が孤児値になっても、漏洩・NPE・誤集計は発生しない。
-- これは CLAUDE.md §4「ユーザー退会時は匿名化（削除しない）／監査・履歴は値を NULL にせずそのまま残す」方針とも整合する
-- （監査列の値を保持しつつ、proxy_input_records の物理削除を RESTRICT に阻まれず貫通させる）。参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。
--
-- 本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 番人テスト（FlywayExistingDataProxyRestrictFkMigrationTest）が守る不変条件は
-- 「参照元の子行をシードし、参照先 proxy_input_records 行を（テスト内で）物理 DELETE しても、
--  RESTRICT が撤廃済みゆえ物理 DELETE がブロックされず、子行は孤児 proxy_input_record_id 値を保持し続ける」ことである。
--
-- ━━━ 対象一覧（2件・すべて明示名・すべて既定 ON DELETE RESTRICT → proxy_input_records）━━━
--  1. schedule_attendances / fk_sa_proxy_record (proxy_input_record_id → proxy_input_records)  @V18.017
--  2. survey_responses     / fk_sr_proxy_record (proxy_input_record_id → proxy_input_records)  @V18.016
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
-- 結論: 2件とも CREATE INDEX 追加は不要。理由は (B) 系統:
--   両列とも「F14.1 で追加された冷たい監査列（proxy_input_record_id）」であり、当該列を先頭にした逆引き finder が存在しない
--   （proxy_input_records への前向き参照のみ。Entity に @Column マッピングはあるが Repository クエリ/JPQL/JOIN は 0 件）。
--   → FK 撤廃でバッキング index が消えても再作成不要。
--  1. schedule_attendances.proxy_input_record_id : (B) 冷たい監査列。逆引き finder なし → index 追加不要。
--  2. survey_responses.proxy_input_record_id     : (B) 冷たい監査列。逆引き finder なし → index 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   各テーブルの他 FK は触らない。
--    ・schedule_attendances → schedules（fk_sa_schedule CASCADE・同一 schedule ドメイン）。
--        ※ fk_sa_user（→ users）は Phase 1-A V62.004 で撤廃済。
--    ・survey_responses → surveys（fk_survey_responses_survey CASCADE・同一 survey ドメイン）/
--                          → survey_questions（fk_survey_responses_question CASCADE・同一 survey ドメイン）/
--                          → survey_options（fk_survey_responses_option SET NULL・同一 survey ドメイン）。
--        ※ fk_survey_responses_user（→ users）は Phase 1-A V62.011 で撤廃済。
--   上記の「proxy_input_records を親とする ON DELETE RESTRICT」2件のみを DROP する。

-- ===== schedule ドメイン =====

-- 1. schedule_attendances.proxy_input_record_id → proxy_input_records (RESTRICT) クロスドメイン参照（代理入力監査列）
--    撤廃only。F14.1 で追加された冷たい監査列で逆引き finder なし → index 追加不要。
ALTER TABLE schedule_attendances DROP FOREIGN KEY fk_sa_proxy_record;

-- ===== survey ドメイン =====

-- 2. survey_responses.proxy_input_record_id → proxy_input_records (RESTRICT) クロスドメイン参照（代理入力監査列）
--    撤廃only。F14.1 で追加された冷たい監査列で逆引き finder なし → index 追加不要。
ALTER TABLE survey_responses DROP FOREIGN KEY fk_sr_proxy_record;

-- ★★★ これにてクロスドメイン FK 撤廃キャンペーン完結（baseline 空＝クロスドメイン FK 全廃・158 → 0 到達）★★★
