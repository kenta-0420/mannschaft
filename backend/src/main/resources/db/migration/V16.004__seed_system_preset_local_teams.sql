INSERT INTO visibility_templates (owner_user_id, name, description, icon_emoji, is_system_preset, preset_key)
VALUES (NULL, '地域のチーム', '自チーム＋地域属性が合致するチーム', '🏘️', TRUE, 'PRESET_LOCAL_TEAMS')
ON DUPLICATE KEY UPDATE preset_key = preset_key;

SET @preset_id = (SELECT id FROM visibility_templates WHERE preset_key = 'PRESET_LOCAL_TEAMS');

INSERT INTO visibility_template_rules (template_id, rule_type, rule_target_id, rule_target_text, sort_order)
VALUES
  (@preset_id, 'TEAM_MEMBER_OF', NULL, '@USER_PRIMARY_TEAM', 0),
  (@preset_id, 'REGION_MATCH', NULL, '@USER_PRIMARY_REGION', 1)
ON DUPLICATE KEY UPDATE sort_order = sort_order;
