-- 本人の全件取得・匿名化と、保持期限を過ぎた監査の検索に使う。
ALTER TABLE module_event_resume_audits
    ADD INDEX idx_module_event_resume_audits_actor_created (actor_user_id, created_at),
    ADD INDEX idx_module_event_resume_audits_created (created_at);
