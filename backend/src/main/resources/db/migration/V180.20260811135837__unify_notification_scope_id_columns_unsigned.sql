-- 符号揃え 第一波（issue #2545）:
-- notifications / scope 系ドメインの _id 列で符号性が不統一（BIGINT 符号付き）のものを、
-- 主キー由来の符号なし BIGINT UNSIGNED（users.id 等）へ揃える。
--
-- なぜ揃えるのか: JOIN/WHERE で符号なし列（例: notifications.id / notifications.scope_id）と
-- 突き合わせる際、片方が符号付きだと MySQL が暗黙の型変換を挟み sargable でなくなる
-- （my_scope_folder_items.scope_id の先例・PR #2703／V177.20260809103622 で実証済み）。
--
-- 何と揃えるのか:
--   - notification_fanout_jobs.{organization_id, source_id, actor_id, cursor_subject_id}
--     … notifications.{organization_id 相当, source_id, actor_id}（BIGINT UNSIGNED・V4.019）と
--       同じ意味の論理参照列。cursor_subject_id は受信者 subject_id（users.id 等）の値域を持つカーソル。
--   - notifications_archive.{user_id, organization_id, source_id, scope_id, actor_id}
--     … 移送元 notifications の同名列（すべて BIGINT UNSIGNED・V4.019）とそのまま揃える。
--       NotificationCleanupBatchService が
--       `DELETE FROM notifications WHERE ... AND id IN (SELECT id FROM notifications_archive)` で
--       notifications.id（UNSIGNED）と notifications_archive.id を突き合わせており、
--       他列も含め表全体の符号性を notifications 側へ統一する。
--   - dashboard_scope_tab_order.{user_id, scope_id}
--     … user_id は users.id、scope_id はチーム/組織 ID（いずれも BIGINT UNSIGNED）への論理参照。
--
-- いずれもクロスドメイン FK は張られていない（原則1・各表 DDL ヘッダに明記済み）ため、
-- FK 起因の ALTER 失敗は発生しない。

-- ---------------------------------------------------------------------
-- 番人 — 負値が存在すると UNSIGNED 変換で値が破壊されるため、変換前に検査して中断する
-- （SIGNAL / IF は stored program の外では使えないため、手続きを一時的に作って CALL し直ちに破棄する。
--  V177.20260809103622 の先例に倣う）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS w1_assert_no_negative_id_columns;

CREATE PROCEDURE w1_assert_no_negative_id_columns()
BEGIN
    DECLARE negative_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO negative_count FROM notification_fanout_jobs
     WHERE organization_id < 0 OR source_id < 0 OR actor_id < 0 OR cursor_subject_id < 0;
    IF negative_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'notification_fanout_jobs に負値あり。UNSIGNED変換中断';
    END IF;

    SELECT COUNT(*) INTO negative_count FROM notifications_archive
     WHERE user_id < 0 OR organization_id < 0 OR source_id < 0 OR scope_id < 0 OR actor_id < 0;
    IF negative_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'notifications_archive に負値あり。UNSIGNED変換中断';
    END IF;

    SELECT COUNT(*) INTO negative_count FROM dashboard_scope_tab_order
     WHERE user_id < 0 OR scope_id < 0;
    IF negative_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'dashboard_scope_tab_order に負値あり。UNSIGNED変換中断';
    END IF;
END;

CALL w1_assert_no_negative_id_columns();

DROP PROCEDURE w1_assert_no_negative_id_columns;

-- ---------------------------------------------------------------------
-- 本体変更 — MODIFY COLUMN は定義を丸ごと置き換えるため、NULL 許容・DEFAULT・COMMENT を
-- 元の宣言（V173.20260730033806 / V173.20260730033807 / V70.020）から一字一句転記する。
-- ---------------------------------------------------------------------

ALTER TABLE notification_fanout_jobs
    MODIFY COLUMN organization_id   BIGINT UNSIGNED NULL
        COMMENT 'テナント（論理参照・FK なし。SYSTEM 通知は NULL）',
    MODIFY COLUMN source_id         BIGINT UNSIGNED NULL
        COMMENT 'ソースID（論理参照・FK なし）',
    MODIFY COLUMN actor_id          BIGINT UNSIGNED NULL
        COMMENT '実行者ID（論理参照・FK なし・システム発火は NULL）',
    MODIFY COLUMN cursor_subject_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'キーセット再開カーソル（処理済み受信者 subject_id 上端。クラッシュ再開の要・AC-2）';

ALTER TABLE notifications_archive
    MODIFY COLUMN user_id         BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL
        COMMENT 'テナント（論理参照・FK なし）',
    MODIFY COLUMN source_id       BIGINT UNSIGNED NULL,
    MODIFY COLUMN scope_id        BIGINT UNSIGNED NULL,
    MODIFY COLUMN actor_id        BIGINT UNSIGNED NULL;

ALTER TABLE dashboard_scope_tab_order
    MODIFY COLUMN user_id  BIGINT UNSIGNED NOT NULL
        COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則）',
    MODIFY COLUMN scope_id BIGINT UNSIGNED NOT NULL
        COMMENT 'チームID または 組織ID（FK制約なし）';
