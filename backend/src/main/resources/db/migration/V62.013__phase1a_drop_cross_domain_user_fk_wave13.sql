-- =============================================================================
-- Phase 1-A wave13: user_id クロスドメインFK 撤廃（第五陣 30件）
-- 対象: committee/facility/chart/service/equipment/payment/property/storage 系
-- 設計原則: CLAUDE.md §「DB設計の原則 1. クロスドメインFKは作らない」
--
-- 方針:
--   1. ALTER TABLE ... DROP FOREIGN KEY で FK 制約を除去
--   2. 元の FK カラムにインデックスが存在しない場合は CREATE INDEX で追加
--   3. auth ドメイン内（users 自身）の FK は対象外
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. committee_invitations — fk_ci_invitee（invitee_user_id → users）
--    invitee_user_id: idx_committee_invitations_invitee (invitee_user_id, resolved_at) 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE committee_invitations
    DROP FOREIGN KEY fk_ci_invitee;

-- -----------------------------------------------------------------------------
-- 2. committee_invitations — fk_ci_invited_by（invited_by → users）
--    invited_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE committee_invitations
    DROP FOREIGN KEY fk_ci_invited_by;
CREATE INDEX idx_committee_invitations_invited_by ON committee_invitations(invited_by);

-- -----------------------------------------------------------------------------
-- 3. committees — fk_committees_created_by（created_by → users）
--    created_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE committees
    DROP FOREIGN KEY fk_committees_created_by;
CREATE INDEX idx_committees_created_by ON committees(created_by);

-- -----------------------------------------------------------------------------
-- 4. blog_post_reactions — fk_bpreact_user（user_id → users）
--    user_id: idx_bpreact_user 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE blog_post_reactions
    DROP FOREIGN KEY fk_bpreact_user;

-- -----------------------------------------------------------------------------
-- 5. my_scope_folders — fk_msf_user（user_id → users）
--    user_id: idx_msf_user_scope (user_id, scope_type, deleted_at) 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE my_scope_folders
    DROP FOREIGN KEY fk_msf_user;

-- -----------------------------------------------------------------------------
-- 6. facility_bookings — fk_fb_booked_by（booked_by → users）
--    booked_by: idx_fb_user (booked_by) 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE facility_bookings
    DROP FOREIGN KEY fk_fb_booked_by;

-- -----------------------------------------------------------------------------
-- 7. facility_bookings — fk_fb_created_by_admin（created_by_admin → users）
--    created_by_admin: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE facility_bookings
    DROP FOREIGN KEY fk_fb_created_by_admin;
CREATE INDEX idx_fb_created_by_admin ON facility_bookings(created_by_admin);

-- -----------------------------------------------------------------------------
-- 8. facility_bookings — fk_fb_approved_by（approved_by → users）
--    approved_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE facility_bookings
    DROP FOREIGN KEY fk_fb_approved_by;
CREATE INDEX idx_fb_approved_by ON facility_bookings(approved_by);

-- -----------------------------------------------------------------------------
-- 9. facility_bookings — fk_fb_cancelled_by（cancelled_by → users）
--    cancelled_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE facility_bookings
    DROP FOREIGN KEY fk_fb_cancelled_by;
CREATE INDEX idx_fb_cancelled_by ON facility_bookings(cancelled_by);

-- -----------------------------------------------------------------------------
-- 10. facility_booking_payments — fk_fbp_payer（payer_user_id → users）
--     payer_user_id: idx_fbp_payer 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE facility_booking_payments
    DROP FOREIGN KEY fk_fbp_payer;

-- -----------------------------------------------------------------------------
-- 11. shared_facilities — fk_sf_created_by（created_by → users）
--     created_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE shared_facilities
    DROP FOREIGN KEY fk_sf_created_by;
CREATE INDEX idx_sf_created_by ON shared_facilities(created_by);

-- -----------------------------------------------------------------------------
-- 12. storage_usage_logs — fk_sul_actor（actor_id → users）
--     actor_id: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE storage_usage_logs
    DROP FOREIGN KEY fk_sul_actor;
CREATE INDEX idx_sul_actor ON storage_usage_logs(actor_id);

-- -----------------------------------------------------------------------------
-- 13. team_friend_folder_members — fk_tffm_added_by（added_by → users）
--     added_by: idx_tffm_added_by 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE team_friend_folder_members
    DROP FOREIGN KEY fk_tffm_added_by;

-- -----------------------------------------------------------------------------
-- 14. multipart_upload_sessions — fk_mup_uploader（uploader_id → users）
--     uploader_id: idx_mup_uploader 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE multipart_upload_sessions
    DROP FOREIGN KEY fk_mup_uploader;

-- -----------------------------------------------------------------------------
-- 15. performance_monthly_summaries — fk_pms_user（user_id → users）
--     user_id: idx_pms_user_month 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE performance_monthly_summaries
    DROP FOREIGN KEY fk_pms_user;

-- -----------------------------------------------------------------------------
-- 16. chart_records — fk_cr_customer（customer_user_id → users）
--     customer_user_id: idx_cr_customer 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE chart_records
    DROP FOREIGN KEY fk_cr_customer;

-- -----------------------------------------------------------------------------
-- 17. chart_records — fk_cr_staff（staff_user_id → users）
--     staff_user_id: idx_cr_staff 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE chart_records
    DROP FOREIGN KEY fk_cr_staff;

-- -----------------------------------------------------------------------------
-- 18. equipment_assignments — fk_ea_assigned_to（assigned_to_user_id → users）
--     assigned_to_user_id: idx_ea_user (assigned_to_user_id, returned_at) 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE equipment_assignments
    DROP FOREIGN KEY fk_ea_assigned_to;

-- -----------------------------------------------------------------------------
-- 19. equipment_assignments — fk_ea_assigned_by（assigned_by_user_id → users）
--     assigned_by_user_id: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE equipment_assignments
    DROP FOREIGN KEY fk_ea_assigned_by;
CREATE INDEX idx_ea_assigned_by ON equipment_assignments(assigned_by_user_id);

-- -----------------------------------------------------------------------------
-- 20. equipment_assignments — fk_ea_returned_by（returned_by_user_id → users）
--     returned_by_user_id: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE equipment_assignments
    DROP FOREIGN KEY fk_ea_returned_by;
CREATE INDEX idx_ea_returned_by ON equipment_assignments(returned_by_user_id);

-- -----------------------------------------------------------------------------
-- 21. member_payments — fk_member_pay_user（user_id → users）
--     user_id: idx_mp_user_item (user_id, payment_item_id) 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE member_payments
    DROP FOREIGN KEY fk_member_pay_user;

-- -----------------------------------------------------------------------------
-- 22. member_payments — fk_mp_recorded_by（recorded_by → users）
--     recorded_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE member_payments
    DROP FOREIGN KEY fk_mp_recorded_by;
CREATE INDEX idx_mp_recorded_by ON member_payments(recorded_by);

-- -----------------------------------------------------------------------------
-- 23. stripe_customers — fk_sc_user（user_id → users）
--     user_id: uq_sc_user UNIQUE KEY 存在（インデックス済み） → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE stripe_customers
    DROP FOREIGN KEY fk_sc_user;

-- -----------------------------------------------------------------------------
-- 24. payment_items — fk_pi_created_by（created_by → users）
--     created_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE payment_items
    DROP FOREIGN KEY fk_pi_created_by;
CREATE INDEX idx_pi_created_by ON payment_items(created_by);

-- -----------------------------------------------------------------------------
-- 25. service_records — fk_sr_member（member_user_id → users）
--     member_user_id: idx_sr_member 存在 → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE service_records
    DROP FOREIGN KEY fk_sr_member;

-- -----------------------------------------------------------------------------
-- 26. service_records — fk_sr_staff（staff_user_id → users）
--     staff_user_id: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE service_records
    DROP FOREIGN KEY fk_sr_staff;
CREATE INDEX idx_sr_staff ON service_records(staff_user_id);

-- -----------------------------------------------------------------------------
-- 27. property_work_packages — fk_pwp_updated_by（updated_by → users）
--     updated_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE property_work_packages
    DROP FOREIGN KEY fk_pwp_updated_by;
CREATE INDEX idx_pwp_updated_by ON property_work_packages(updated_by);

-- -----------------------------------------------------------------------------
-- 28. team_member_info_responses — fk_tmir_user（user_id → users）
--     user_id: uq_tmir_user_field UNIQUE KEY の先頭カラム（インデックス済み） → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE team_member_info_responses
    DROP FOREIGN KEY fk_tmir_user;

-- -----------------------------------------------------------------------------
-- 29. service_record_reactions — fk_srr_user（user_id → users）
--     user_id: uq_srr_record_user UNIQUE KEY の一部（インデックス済み） → 追加不要
-- -----------------------------------------------------------------------------
ALTER TABLE service_record_reactions
    DROP FOREIGN KEY fk_srr_user;

-- -----------------------------------------------------------------------------
-- 30. performance_records — fk_pr_recorded_by（recorded_by → users）
--     recorded_by: インデックスなし → 追加
-- -----------------------------------------------------------------------------
ALTER TABLE performance_records
    DROP FOREIGN KEY fk_pr_recorded_by;
CREATE INDEX idx_pr_recorded_by ON performance_records(recorded_by);

-- =============================================================================
-- wave13 完了: 30件撤廃
--   committee_invitations(2) / committees(1) / blog_post_reactions(1)
--   my_scope_folders(1) / facility_bookings(4) / facility_booking_payments(1)
--   shared_facilities(1) / storage_usage_logs(1) / team_friend_folder_members(1)
--   multipart_upload_sessions(1) / performance_monthly_summaries(1)
--   chart_records(2) / equipment_assignments(3) / member_payments(2)
--   stripe_customers(1) / payment_items(1) / service_records(2)
--   property_work_packages(1) / team_member_info_responses(1)
--   service_record_reactions(1) / performance_records(1)
-- =============================================================================
