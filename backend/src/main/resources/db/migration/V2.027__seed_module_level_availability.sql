-- モジュール×レベル別利用可否シードデータ（全39モジュール×3レベル=117件）
-- 基本: TEAM=true, ORGANIZATION=false, PERSONAL=false
-- 例外: dashboard/todo/schedule/messaging/chat/push_notification/search/i18n/theme は全レベルtrue

-- 1. dashboard（全レベルtrue）
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at) VALUES
(1, 'ORGANIZATION', 1, '組織ダッシュボード', NOW(), NOW()),
(1, 'TEAM', 1, NULL, NOW(), NOW()),
(1, 'PERSONAL', 1, '個人ダッシュボード', NOW(), NOW()),
-- 2. todo（全レベルtrue）
(2, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(2, 'TEAM', 1, NULL, NOW(), NOW()),
(2, 'PERSONAL', 1, '個人TODO', NOW(), NOW()),
-- 3. timeline
(3, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(3, 'TEAM', 1, NULL, NOW(), NOW()),
(3, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 4. messaging（全レベルtrue）
(4, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(4, 'TEAM', 1, NULL, NOW(), NOW()),
(4, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 5. chat（全レベルtrue）
(5, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(5, 'TEAM', 1, NULL, NOW(), NOW()),
(5, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 6. push_notification（全レベルtrue）
(6, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(6, 'TEAM', 1, NULL, NOW(), NOW()),
(6, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 7. survey
(7, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(7, 'TEAM', 1, NULL, NOW(), NOW()),
(7, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 8. file_sharing
(8, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(8, 'TEAM', 1, NULL, NOW(), NOW()),
(8, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 9. search（全レベルtrue）
(9, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(9, 'TEAM', 1, NULL, NOW(), NOW()),
(9, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 10. moderation
(10, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(10, 'TEAM', 1, NULL, NOW(), NOW()),
(10, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 11. mention
(11, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(11, 'TEAM', 1, NULL, NOW(), NOW()),
(11, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 12. audit_log
(12, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(12, 'TEAM', 1, NULL, NOW(), NOW()),
(12, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 13. data_export
(13, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(13, 'TEAM', 1, NULL, NOW(), NOW()),
(13, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 14. i18n（全レベルtrue）
(14, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(14, 'TEAM', 1, NULL, NOW(), NOW()),
(14, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 15. schedule（全レベルtrue）
(15, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(15, 'TEAM', 1, NULL, NOW(), NOW()),
(15, 'PERSONAL', 1, '個人スケジュール', NOW(), NOW()),
-- 16. activity_record
(16, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(16, 'TEAM', 1, NULL, NOW(), NOW()),
(16, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 17. bulletin
(17, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(17, 'TEAM', 1, NULL, NOW(), NOW()),
(17, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 18. matching
(18, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(18, 'TEAM', 1, NULL, NOW(), NOW()),
(18, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 19. performance
(19, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(19, 'TEAM', 1, NULL, NOW(), NOW()),
(19, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 20. member_intro
(20, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(20, 'TEAM', 1, NULL, NOW(), NOW()),
(20, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 21. circular
(21, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(21, 'TEAM', 1, NULL, NOW(), NOW()),
(21, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 22. digital_seal
(22, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(22, 'TEAM', 1, NULL, NOW(), NOW()),
(22, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 23. safety_check
(23, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(23, 'TEAM', 1, NULL, NOW(), NOW()),
(23, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 24. analytics
(24, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(24, 'TEAM', 1, NULL, NOW(), NOW()),
(24, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 25. theme（全レベルtrue）
(25, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(25, 'TEAM', 1, NULL, NOW(), NOW()),
(25, 'PERSONAL', 1, NULL, NOW(), NOW()),
-- 26. direct_mail
(26, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(26, 'TEAM', 1, NULL, NOW(), NOW()),
(26, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 27. ad_display
(27, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(27, 'TEAM', 1, NULL, NOW(), NOW()),
(27, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 28. corkboard
(28, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(28, 'TEAM', 1, NULL, NOW(), NOW()),
(28, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- OPTIONAL: 29. qr_membership
(29, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(29, 'TEAM', 1, NULL, NOW(), NOW()),
(29, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 30. payment
(30, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(30, 'TEAM', 1, NULL, NOW(), NOW()),
(30, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 31. reservation
(31, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(31, 'TEAM', 1, 'チーム＝枠管理', NOW(), NOW()),
(31, 'PERSONAL', 1, '個人＝予約のみ', NOW(), NOW()),
-- 32. service_record
(32, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(32, 'TEAM', 1, NULL, NOW(), NOW()),
(32, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 33. equipment
(33, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(33, 'TEAM', 1, NULL, NOW(), NOW()),
(33, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 34. gallery
(34, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(34, 'TEAM', 1, NULL, NOW(), NOW()),
(34, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 35. shift
(35, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(35, 'TEAM', 1, NULL, NOW(), NOW()),
(35, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 36. voting
(36, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(36, 'TEAM', 1, NULL, NOW(), NOW()),
(36, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 37. resident_register
(37, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(37, 'TEAM', 1, NULL, NOW(), NOW()),
(37, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 38. parking
(38, 'ORGANIZATION', 1, NULL, NOW(), NOW()),
(38, 'TEAM', 1, NULL, NOW(), NOW()),
(38, 'PERSONAL', 0, NULL, NOW(), NOW()),
-- 39. medical_record
(39, 'ORGANIZATION', 0, NULL, NOW(), NOW()),
(39, 'TEAM', 1, NULL, NOW(), NOW()),
(39, 'PERSONAL', 0, NULL, NOW(), NOW());
