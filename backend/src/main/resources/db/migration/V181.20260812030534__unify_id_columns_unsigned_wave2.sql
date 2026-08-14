-- 符号揃え 第二波（issue #2545）:
-- 主キー（users.id 等）に由来する `_id` 列は本来すべて BIGINT UNSIGNED で揃えるべきだが、
-- 過去のマイグレーションで符号付き BIGINT のまま作られたものが散在している。
-- JOIN/WHERE で符号なし列（主キー側）と突き合わせる際、片方が符号付きだと MySQL が
-- 暗黙の型変換を挟み sargable でなくなる（my_scope_folder_items.scope_id の先例・
-- PR #2703／V177.20260809103622、および notifications/scope 系・PR #2742／
-- V180.20260811135837 の第一波で実証済み）。
--
-- 第一波との関係: 第一波は notification_fanout_jobs / notifications_archive /
-- dashboard_scope_tab_order の10列を是正した。本波（第二波）は origin/main 全体の
-- migration を最終状態で横断し、それ以外に残っていた符号付き `_id` 列を洗い出して是正する。
--
-- 除外（第三波へ持ち越し）:
--   attendance_requirement_evaluations.{requirement_rule_id, summary_id}
--     … 参照先 attendance_requirement_rules.id / student_attendance_summaries.id が
--       それ自体まだ符号付き BIGINT（主キー側の是正）であり、FK 型不一致で本波の
--       ALTER が失敗するため対象外とした。親テーブルの主キー是正とあわせて別途扱う。
--
-- Entity 側（Java）は Long のまま変更不要（Java に符号なし整数型は無い）。
-- 本マイグレーションは新規ファイルであり、Flyway チェックサム不一致は起きない。

-- ---------------------------------------------------------------------
-- 番人 — 負値が存在すると UNSIGNED 変換で値が破壊されるため、変換前に検査して中断する
-- （SIGNAL / IF は stored program の外では使えないため、手続きを一時的に作って CALL し直ちに破棄する。
--  V177.20260809103622 / V180.20260811135837 の先例に倣う。SIGNAL MESSAGE_TEXT は128文字上限）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS w2_assert_no_negative_id_columns;

CREATE PROCEDURE w2_assert_no_negative_id_columns()
BEGIN
    DECLARE negative_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO negative_count FROM reservation_team_settings WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reservation_team_settingsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM reservation_policies WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reservation_policiesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM gdpr_s3_purge_failures WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gdpr_s3_purge_failuresに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM reflection_themes WHERE user_id < 0 OR linked_slot_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reflection_themesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM reflection_entries WHERE user_id < 0 OR exported_blog_post_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reflection_entriesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM recall_attempts WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'recall_attemptsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM reflection_spaced_reminders WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reflection_spaced_remindersに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM user_reflection_settings WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'user_reflection_settingsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM appearance_settings WHERE user_id < 0 OR seasonal_theme_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appearance_settingsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM payment_beneficiary_settings WHERE team_id < 0 OR organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_beneficiary_settingsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM google_calendar_webhook_channels WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'google_calendar_webhook_channelsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM reservation_notification_recipients WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reservation_notification_recipientsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM visibility_template_rules WHERE rule_target_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'visibility_template_rulesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM village_charter_drafters WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'village_charter_draftersに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM event_care_notification_logs WHERE notification_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'event_care_notification_logsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM attendance_location_changes WHERE team_id < 0 OR student_user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attendance_location_changesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM attendance_requirement_rules
     WHERE organization_id < 0 OR team_id < 0 OR term_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attendance_requirement_rulesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM student_attendance_summaries
     WHERE team_id < 0 OR student_user_id < 0 OR term_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'student_attendance_summariesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM email_outbox WHERE user_id < 0 OR organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'email_outboxに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM public_post_comments WHERE post_id < 0 OR author_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'public_post_commentsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM schedule_scheduled_tasks
     WHERE schedule_id < 0 OR organization_id < 0 OR scope_id < 0 OR materialized_entity_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'schedule_scheduled_tasksに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM matches
     WHERE organization_id < 0 OR team_id < 0 OR tournament_fixture_id < 0
        OR schedule_id < 0 OR opponent_team_id < 0 OR scorekeeper_user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'matchesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM match_events
     WHERE player_user_id < 0 OR related_player_user_id < 0 OR recorded_by_team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'match_eventsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM player_appearances WHERE player_user_id < 0 OR owning_team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'player_appearancesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_scorekeepers WHERE tournament_id < 0 OR user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tournament_scorekeepersに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM team_slug_history WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'team_slug_historyに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM organization_slug_history WHERE organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'organization_slug_historyに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM beta_restriction_config WHERE max_team_id < 0 OR max_org_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'beta_restriction_configに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM team_name_disclosure_change_logs WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'team_name_disclosure_change_logsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM organization_name_disclosure_change_logs WHERE organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'organization_name_disclosure_change_logsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM user_nav_settings WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'user_nav_settingsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM inbox_item_states WHERE user_id < 0 OR source_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inbox_item_statesに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM notification_labels WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'notification_labelsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM inbox_label_links WHERE user_id < 0 OR source_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inbox_label_linksに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_fee
     WHERE tournament_id < 0 OR division_id < 0 OR payment_item_id < 0 OR organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tournament_feeに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_fee_target WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tournament_fee_targetに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM league_transfer
     WHERE team_id < 0 OR from_organization_id < 0 OR to_organization_id < 0
        OR source_division_id < 0 OR target_division_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'league_transferに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM team_uniform_set WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'team_uniform_setに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM match_roster_staff
     WHERE match_id < 0 OR participant_id < 0 OR user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'match_roster_staffに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_entry_template_staff WHERE user_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tournament_entry_template_staffに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_submission_requirement
     WHERE tournament_id < 0 OR division_id < 0 OR form_template_id < 0 OR organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'tournament_submission_requirementに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM tournament_submission_requirement_target WHERE team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'submission_requirement_targetに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM match_score_entries WHERE competitor_user_id < 0 OR competitor_team_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'match_score_entriesに負値'; END IF;
END;

CALL w2_assert_no_negative_id_columns();

DROP PROCEDURE w2_assert_no_negative_id_columns;

-- ---------------------------------------------------------------------
-- 本体変更 — MODIFY COLUMN は定義を丸ごと置き換えるため、NULL 許容・DEFAULT・COMMENT を
-- 元の宣言（各テーブルの CREATE TABLE 文）から一字一句転記する。
-- FK 確認: 本波対象列にはいずれも FK 制約が張られていない（原則1・各表 DDL ヘッダに明記済み）ため、
-- FK 起因の ALTER 失敗は発生しない。
-- ---------------------------------------------------------------------

-- ===== reservation ドメイン =====
ALTER TABLE reservation_team_settings
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE reservation_policies
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE reservation_notification_recipients
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL;

-- ===== gdpr ドメイン =====
ALTER TABLE gdpr_s3_purge_failures
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

-- ===== reflection ドメイン =====
ALTER TABLE reflection_themes
    MODIFY COLUMN user_id       BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN linked_slot_id BIGINT UNSIGNED NULL;

ALTER TABLE reflection_entries
    MODIFY COLUMN user_id                BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN exported_blog_post_id  BIGINT UNSIGNED NULL;

ALTER TABLE recall_attempts
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE reflection_spaced_reminders
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE user_reflection_settings
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

-- ===== appearance ドメイン =====
ALTER TABLE appearance_settings
    MODIFY COLUMN user_id           BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN seasonal_theme_id BIGINT UNSIGNED NULL;

-- ===== payment ドメイン =====
ALTER TABLE payment_beneficiary_settings
    MODIFY COLUMN team_id         BIGINT UNSIGNED NULL,
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL;

-- ===== calendar 連携ドメイン =====
ALTER TABLE google_calendar_webhook_channels
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

-- ===== visibility ドメイン =====
ALTER TABLE visibility_template_rules
    MODIFY COLUMN rule_target_id BIGINT UNSIGNED NULL;

-- ===== village ドメイン =====
ALTER TABLE village_charter_drafters
    MODIFY COLUMN user_id BIGINT UNSIGNED NULL
        COMMENT '策定者（FK非付与・退会時 NULL 化・原則1/4）';

-- ===== event care ドメイン =====
ALTER TABLE event_care_notification_logs
    MODIFY COLUMN notification_id BIGINT UNSIGNED NULL COMMENT 'FK → notifications.id（配信レコード参照）';

-- ===== attendance ドメイン =====
ALTER TABLE attendance_location_changes
    MODIFY COLUMN team_id         BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN student_user_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE attendance_requirement_rules
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL COMMENT '組織スコープ（team_id と排他）',
    MODIFY COLUMN team_id         BIGINT UNSIGNED NULL COMMENT 'チームスコープ（organization_id と排他）',
    MODIFY COLUMN term_id         BIGINT UNSIGNED NULL COMMENT 'NULLなら年度通算';

ALTER TABLE student_attendance_summaries
    MODIFY COLUMN team_id         BIGINT UNSIGNED NOT NULL COMMENT 'チームID',
    MODIFY COLUMN student_user_id BIGINT UNSIGNED NOT NULL COMMENT '生徒ユーザーID',
    MODIFY COLUMN term_id         BIGINT UNSIGNED NULL     COMMENT 'NULLなら年度通算';

-- ===== email ドメイン =====
ALTER TABLE email_outbox
    MODIFY COLUMN user_id         BIGINT UNSIGNED NULL COMMENT '宛先ユーザー (論理参照、FK なし)',
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL COMMENT '所属組織 (論理参照、FK なし。認証メールは NULL)';

-- ===== blog ドメイン =====
ALTER TABLE public_post_comments
    MODIFY COLUMN post_id   BIGINT UNSIGNED NOT NULL COMMENT '対象 BlogPost の ID',
    MODIFY COLUMN author_id BIGINT UNSIGNED NOT NULL COMMENT '投稿者ユーザー ID（users.id）';

-- ===== schedule ドメイン =====
ALTER TABLE schedule_scheduled_tasks
    MODIFY COLUMN schedule_id            BIGINT UNSIGNED NOT NULL COMMENT '親予定 schedules.id（FK制約なし・論理参照）',
    MODIFY COLUMN organization_id        BIGINT UNSIGNED NOT NULL COMMENT 'テナントキー。team予定なら所属組織のid（原則7）',
    MODIFY COLUMN scope_id               BIGINT UNSIGNED NOT NULL COMMENT 'スコープ実体ID（team_id または organization_id）',
    MODIFY COLUMN materialized_entity_id BIGINT UNSIGNED NULL     COMMENT '生成後の実体id（event_survey / schedule_attendance 等）';

-- ===== match/tournament ドメイン =====
ALTER TABLE matches
    MODIFY COLUMN organization_id       BIGINT UNSIGNED NULL     COMMENT '組織スコープ（単独チーム試合は NULL・FK なし）',
    MODIFY COLUMN team_id               BIGINT UNSIGNED NOT NULL COMMENT '記録/ホーム主体チーム（team ドメイン ID 参照・FK なし）',
    MODIFY COLUMN tournament_fixture_id BIGINT UNSIGNED NULL     COMMENT '大会 fixture リンク（tournament ドメインへの BIGINT ID 参照・NULL=単独試合・FK なし）',
    MODIFY COLUMN schedule_id           BIGINT UNSIGNED NULL     COMMENT 'カレンダー連携（F03.1・schedules への BIGINT ID 参照・FK なし）',
    MODIFY COLUMN opponent_team_id      BIGINT UNSIGNED NULL     COMMENT '登録相手チーム（team ドメイン ID 参照・NULL 可・FK なし）',
    MODIFY COLUMN scorekeeper_user_id   BIGINT UNSIGNED NULL     COMMENT '記録係ユーザー（公式戦・user ドメイン ID 参照・FK なし）';

ALTER TABLE match_events
    MODIFY COLUMN player_user_id         BIGINT UNSIGNED NULL COMMENT '主体選手（user ドメイン ID 参照・未登録は NULL・FK なし）',
    MODIFY COLUMN related_player_user_id BIGINT UNSIGNED NULL COMMENT '関連選手（アシスト者/交代相手・user ドメイン ID 参照・FK なし）',
    MODIFY COLUMN recorded_by_team_id    BIGINT UNSIGNED NULL COMMENT '記録したチーム（共同記録の権限判定・team ドメイン ID 参照・NULL=記録係記録・FK なし）';

ALTER TABLE player_appearances
    MODIFY COLUMN player_user_id BIGINT UNSIGNED NULL     COMMENT '選手（user ドメイン ID 参照・未登録は NULL・FK なし）',
    MODIFY COLUMN owning_team_id BIGINT UNSIGNED NOT NULL COMMENT '自チーム編集権限の判定（team ドメイン ID 参照・FK なし）';

ALTER TABLE tournament_scorekeepers
    MODIFY COLUMN tournament_id BIGINT UNSIGNED NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    MODIFY COLUMN user_id       BIGINT UNSIGNED NOT NULL COMMENT 'スコアキーパーに指名されたユーザー（users.id・FK なし／原則1）';

ALTER TABLE match_score_entries
    MODIFY COLUMN competitor_user_id BIGINT UNSIGNED NULL COMMENT '出場選手（user ドメイン ID 参照・未登録は NULL・原則1）',
    MODIFY COLUMN competitor_team_id BIGINT UNSIGNED NULL COMMENT '所属チーム（team ドメイン ID 参照・団体採点時・原則1）';

ALTER TABLE match_roster_staff
    MODIFY COLUMN match_id       BIGINT UNSIGNED NOT NULL COMMENT 'tournament_matches.id への ID 参照（同一 tournament ドメイン・FK なし）',
    MODIFY COLUMN participant_id BIGINT UNSIGNED NOT NULL COMMENT 'tournament_participants.id への ID 参照（自チーム分・FK なし）',
    MODIFY COLUMN user_id        BIGINT UNSIGNED NULL     COMMENT '紐付くユーザー（user ドメインへの ID 参照・FK なし／原則1・NULL 可）';

ALTER TABLE tournament_entry_template_staff
    MODIFY COLUMN user_id BIGINT UNSIGNED NULL COMMENT 'user ドメインへの ID 参照（FK なし／原則1・NULL 可）';

ALTER TABLE tournament_fee
    MODIFY COLUMN tournament_id   BIGINT UNSIGNED NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    MODIFY COLUMN division_id     BIGINT UNSIGNED NULL     COMMENT '対象ディビジョン（tournament_divisions.id。NULL=大会全体・FK なし）',
    MODIFY COLUMN payment_item_id BIGINT UNSIGNED NOT NULL COMMENT 'payment ドメインの payment_items.id（FK なし／原則1）',
    MODIFY COLUMN organization_id BIGINT UNSIGNED NOT NULL COMMENT '主催組織（入金先・テナント絞り込み）';

ALTER TABLE tournament_fee_target
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL COMMENT '対象チーム（teams.id・FK なし／原則1）';

ALTER TABLE tournament_submission_requirement
    MODIFY COLUMN tournament_id    BIGINT UNSIGNED NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    MODIFY COLUMN division_id      BIGINT UNSIGNED NULL     COMMENT '対象ディビジョン（tournament_divisions.id。NULL=大会全体・FK なし）',
    MODIFY COLUMN form_template_id BIGINT UNSIGNED NOT NULL COMMENT 'forms/workflow ドメインの form_templates.id（FK なし／原則1）',
    MODIFY COLUMN organization_id  BIGINT UNSIGNED NOT NULL COMMENT '主催組織（テナント絞り込み・クォータ帰属）';

ALTER TABLE tournament_submission_requirement_target
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL COMMENT '対象チーム（teams.id・FK なし／原則1）';

ALTER TABLE league_transfer
    MODIFY COLUMN team_id              BIGINT UNSIGNED NOT NULL COMMENT '移籍対象チーム（teams.id・FK なし／原則1）。team_id は不変',
    MODIFY COLUMN from_organization_id BIGINT UNSIGNED NOT NULL COMMENT '手放す側 org（昇格時=下位県協会 / 降格時=上位協会・FK なし）',
    MODIFY COLUMN to_organization_id   BIGINT UNSIGNED NOT NULL COMMENT '受け入れる側 org（昇格時=上位協会 / 降格時=出身県協会・FK なし）',
    MODIFY COLUMN source_division_id   BIGINT UNSIGNED NULL     COMMENT '移籍元ディビジョン（tournament_divisions.id・FK なし）',
    MODIFY COLUMN target_division_id   BIGINT UNSIGNED NULL     COMMENT '移籍先ディビジョン（承認・配属確定時にセット・FK なし）';

ALTER TABLE team_uniform_set
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL COMMENT 'team ドメインへの ID 参照（teams.id・FK なし／原則1）';

-- ===== slug 履歴ドメイン =====
ALTER TABLE team_slug_history
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL COMMENT 'リネーム対象チーム（teams.id・FK なし／原則1）';

ALTER TABLE organization_slug_history
    MODIFY COLUMN organization_id BIGINT UNSIGNED NOT NULL COMMENT 'リネーム対象組織（organizations.id・FK なし／原則1）';

-- ===== beta 制限 / 名前開示ドメイン =====
ALTER TABLE beta_restriction_config
    MODIFY COLUMN max_team_id BIGINT UNSIGNED NULL COMMENT 'このID以下のチームが招待可能（NULL=制限なし）',
    MODIFY COLUMN max_org_id  BIGINT UNSIGNED NULL COMMENT 'このID以下の組織が招待可能（NULL=制限なし）';

ALTER TABLE team_name_disclosure_change_logs
    MODIFY COLUMN team_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE organization_name_disclosure_change_logs
    MODIFY COLUMN organization_id BIGINT UNSIGNED NOT NULL;

-- ===== nav / inbox ドメイン =====
ALTER TABLE user_nav_settings
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則）';

ALTER TABLE inbox_item_states
    MODIFY COLUMN user_id   BIGINT UNSIGNED NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則1）',
    MODIFY COLUMN source_id BIGINT UNSIGNED NOT NULL COMMENT '各ソーステーブルのPK（FK制約なし・論理参照）';

ALTER TABLE notification_labels
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id（FK制約なし）';

ALTER TABLE inbox_label_links
    MODIFY COLUMN user_id   BIGINT UNSIGNED NOT NULL COMMENT 'users.id（冗長保持・user絞り込み高速化／所有検証）',
    MODIFY COLUMN source_id BIGINT UNSIGNED NOT NULL COMMENT '各ソースPK（論理参照）';
