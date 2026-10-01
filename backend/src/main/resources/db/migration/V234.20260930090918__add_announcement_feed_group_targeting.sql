-- F01.2.1 組織のチームグループ 部隊 1-B（設計書 §5.6 順5）: announcement_feeds へグループ宛ての3列と多値インデックスを追加する。
-- target_group_ids は UUID 文字列（36文字）の JSON 配列。多値インデックスは CHAR(36) ARRAY。
--
-- 【多値インデックスの件数上限（AC-H14a・実 MySQL 8.0 の実測）】
--   InnoDB の多値インデックスは 1 レコードあたりのキー総バイト数に上限がある（実測 5,352 バイト。
--   超えると ER_EXCEEDED_MAX_TOTAL_LENGTH_OF_VALUES_PER_RECORD で INSERT/UPDATE 自体が失敗する）。
--   CHAR(36) ARRAY は 1 キー 36 バイトなので target_group_ids の上限は 148 件、
--   既存 idx_af_target_teams（UNSIGNED ARRAY・1 キー 8 バイト）の target_team_ids の上限は 669 件。
--   実測は AnnouncementMultiValuedIndexLimitIT が固定している。
ALTER TABLE announcement_feeds
    ADD COLUMN target_group_ids JSON NULL
        COMMENT 'グループ宛て: 送信時に範囲を展開したチームグループID（UUID文字列配列）。表示は閲覧時に所属と照合',
    ADD COLUMN include_unassigned BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'グループ宛て: 未分類チームも表示対象にするか',
    ADD COLUMN target_audience JSON NULL
        COMMENT '送信時の宛先指定の記録（グループ名・範囲・push 宛先数）。表示判定には使わない',
    ADD INDEX idx_af_target_groups ((CAST(target_group_ids->'$[*]' AS CHAR(36) ARRAY)));
