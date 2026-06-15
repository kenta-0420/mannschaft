-- F01.2 §5.9.5 slug リネーム時の 301 リダイレクト基盤（後続 wave ① BE）。
--
-- チーム／組織の slug を後からユーザー本人が変更できるようにする。変更時に「旧 slug」を履歴として
-- 残し、旧 URL アクセスを新 slug へ 301 リダイレクトできるようにする（SEO・ブックマーク保全）。
-- 旧 slug は他チーム／組織が再利用できないよう恒久的に予約する（恒久 301 を壊さないため）。
--
-- 設計書: docs/features/F01.2_org_team_member_role/04_security_operations.md §5.9.5
--
-- 採番: origin/main 全体の最大 major（V87 系）の次として V88.001 を採番する
--       （[[feedback_flyway_version_sort_after_global_max]] 準拠）。新規テーブルゆえ既存データは無く、
--       from-scratch 番人テスト（FlywayFromScratchMigrationTest）で足りる。マージ直前に origin/main の
--       最大 major を再確認し、衝突時はリネームすること（[[feedback_migration_version_collision]]）。
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。team_id / organization_id は ID 値のみ保持し、
--     参照整合性はアプリ層で保証する。teams / organizations への CASCADE も張らない。
--   - 履歴レコードは恒久保持（解決と再利用予約のため）。論理削除カラムは持たない。

CREATE TABLE team_slug_history (
    id         BINARY(16)  NOT NULL COMMENT 'UUIDv7（原則6）',
    team_id    BIGINT      NOT NULL COMMENT 'リネーム対象チーム（teams.id・FK なし／原則1）',
    old_slug   VARCHAR(30) NOT NULL COMMENT 'リネーム前の旧 slug（恒久予約・301 解決のキー）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'リネーム実施日時',

    PRIMARY KEY (id),
    -- 旧 slug は他チームへ再発行させないためグローバル一意（恒久予約）。
    -- 解決時の old_slug 完全一致引きにもこのインデックスが効く。
    UNIQUE KEY uq_team_slug_history_old_slug (old_slug),
    -- 自チームの過去 slug への「戻し」許可判定（team_id 除外）と履歴一覧取得用。
    KEY idx_team_slug_history_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F01.2 §5.9.5 チーム slug リネーム履歴（旧slug→新slug 301解決＋恒久予約）';

CREATE TABLE organization_slug_history (
    id              BINARY(16)  NOT NULL COMMENT 'UUIDv7（原則6）',
    organization_id BIGINT      NOT NULL COMMENT 'リネーム対象組織（organizations.id・FK なし／原則1）',
    old_slug        VARCHAR(30) NOT NULL COMMENT 'リネーム前の旧 slug（恒久予約・301 解決のキー）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'リネーム実施日時',

    PRIMARY KEY (id),
    UNIQUE KEY uq_organization_slug_history_old_slug (old_slug),
    KEY idx_organization_slug_history_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F01.2 §5.9.5 組織 slug リネーム履歴（旧slug→新slug 301解決＋恒久予約）';
