-- メディアの認可スコープをキー文字列から独立させる。
-- 既存の未紐付け行は申告prefixの信頼性を証明できないためNULLのままfail-closedにする。
ALTER TABLE blog_media_uploads
    ADD COLUMN scope_type VARCHAR(20) NULL,
    ADD COLUMN scope_id BIGINT NULL;

UPDATE blog_media_uploads m
JOIN blog_posts p ON p.id = m.blog_post_id AND p.deleted_at IS NULL
SET m.scope_type = CASE WHEN p.team_id IS NOT NULL THEN 'TEAM'
                        WHEN p.organization_id IS NOT NULL THEN 'ORGANIZATION'
                        WHEN p.user_id IS NOT NULL THEN 'PERSONAL' END,
    m.scope_id = COALESCE(p.team_id, p.organization_id, p.user_id);

-- 既存ACLをCLAIMEDへ一括昇格しない。アップロード完了と権限の証跡がある経路だけがclaimする。
