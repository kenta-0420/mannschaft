-- F??（障害告知バナー）: incident_banner_translations テーブル作成
-- incident_banners の多言語翻訳メッセージを管理する。
-- banner_id → incident_banners は同一ドメイン内の親子関係であるため ON DELETE CASCADE を許可（アーキテクチャ原則2）。
CREATE TABLE incident_banner_translations (
    id         BINARY(16)   NOT NULL,
    banner_id  BINARY(16)   NOT NULL,
    language   VARCHAR(10)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_banner_lang (banner_id, language),
    CONSTRAINT fk_ibt_banner FOREIGN KEY (banner_id) REFERENCES incident_banners(id) ON DELETE CASCADE
);
