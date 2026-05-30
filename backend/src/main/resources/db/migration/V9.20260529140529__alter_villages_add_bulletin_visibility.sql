-- F17.1 村掲示板グローバル方式: villages へ掲示板公開範囲列を追加
-- villages.visibility（検索可否 PUBLIC/UNLISTED）とは別概念。
-- bulletin_visibility は村掲示板の閲覧可否を制御する:
--   PUBLIC        = 村の非メンバー（ログイン済）でも掲示板を閲覧可
--   MEMBERS_ONLY  = 村メンバーのみ閲覧可（デフォルト）
-- 元 DDL: V9.125__create_villages.sql（visibility カラムの直後に配置）。

ALTER TABLE villages
    ADD COLUMN bulletin_visibility VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY' COMMENT '掲示板の公開範囲 (PUBLIC/MEMBERS_ONLY)' AFTER visibility;
