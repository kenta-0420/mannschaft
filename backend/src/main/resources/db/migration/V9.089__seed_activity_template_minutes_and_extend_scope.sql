-- F04.10: 議事録標準テンプレートを system_activity_template_presets に追加
-- 委員会で使用する標準議事録テンプレート
INSERT INTO system_activity_template_presets
    (category, name, description, icon, color, is_participant_required, default_visibility, fields_json)
VALUES
    (
        'COMMITTEE',
        '議事録',
        '委員会の議事録テンプレート。日程・出席者・議題・議論内容・決議事項・アクションアイテム・次回日程を記録する',
        '📋',
        '#7C3AED',
        TRUE,
        'MEMBERS_ONLY',
        JSON_ARRAY(
            JSON_OBJECT(
                'field_key', 'meeting_date',
                'field_label', '開催日時',
                'field_type', 'DATETIME',
                'is_required', TRUE,
                'is_aggregatable', FALSE,
                'sort_order', 1
            ),
            JSON_OBJECT(
                'field_key', 'location',
                'field_label', '開催場所',
                'field_type', 'TEXT',
                'is_required', FALSE,
                'is_aggregatable', FALSE,
                'sort_order', 2
            ),
            JSON_OBJECT(
                'field_key', 'agenda',
                'field_label', '議題',
                'field_type', 'TEXTAREA',
                'is_required', TRUE,
                'is_aggregatable', FALSE,
                'sort_order', 3
            ),
            JSON_OBJECT(
                'field_key', 'discussion',
                'field_label', '議論内容',
                'field_type', 'TEXTAREA',
                'is_required', FALSE,
                'is_aggregatable', FALSE,
                'sort_order', 4
            ),
            JSON_OBJECT(
                'field_key', 'decisions',
                'field_label', '決議事項',
                'field_type', 'TEXTAREA',
                'is_required', TRUE,
                'is_aggregatable', FALSE,
                'sort_order', 5
            ),
            JSON_OBJECT(
                'field_key', 'action_items',
                'field_label', 'アクションアイテム（担当者・期限つき）',
                'field_type', 'TEXTAREA',
                'is_required', FALSE,
                'is_aggregatable', FALSE,
                'sort_order', 6
            ),
            JSON_OBJECT(
                'field_key', 'next_meeting',
                'field_label', '次回開催予定',
                'field_type', 'DATE',
                'is_required', FALSE,
                'is_aggregatable', FALSE,
                'sort_order', 7
            )
        )
    );

-- F04.10: activity_results.scope_type コメント更新（COMMITTEE 値を追加）
ALTER TABLE activity_results
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';
