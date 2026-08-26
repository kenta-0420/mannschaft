-- タイムライン投稿の「配下配信」範囲カラムを追加する。
--
-- DIRECT      : 直接所属者のみ（既定・既存行の挙動を一切変えない）
-- CHILDREN    : 直下の子組織まで配信
-- DESCENDANTS : 配下すべて（子孫組織すべて）へ配信
--
-- 既存行は DEFAULT 'DIRECT' で埋まるため、本 migration 適用だけでは可視範囲は変化しない。
-- 実効を持つのは ORGANIZATION スコープの投稿のみ（チームに階層が無いため）。
ALTER TABLE timeline_posts
    ADD COLUMN delivery_scope VARCHAR(20) NOT NULL DEFAULT 'DIRECT'
    COMMENT '配下配信範囲: DIRECT/CHILDREN/DESCENDANTS';

-- マイフィードの祖先組織展開は (scope_type, delivery_scope, scope_id) で絞り込むため、
-- 既存の scope 索引と併せて配信指定付き投稿を素早く弾けるようにする。
CREATE INDEX idx_timeline_posts_delivery_scope
    ON timeline_posts (scope_type, delivery_scope, scope_id);
