-- F01.2.1 組織のチームグループ 部隊 1-B（設計書 §5.6 順6）: announcement_range_templates へグループ宛ての3列を追加する。
ALTER TABLE announcement_range_templates
    ADD COLUMN target_group_ids JSON NULL
        COMMENT '個別選択したチームグループID（UUID文字列配列）',
    ADD COLUMN target_group_range JSON NULL
        COMMENT '並び順の範囲指定 {"from_group_id":..,"to_group_id":..}',
    ADD COLUMN include_unassigned BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '未分類チームを含めるか';
