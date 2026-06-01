-- F08.7.1 / 03 リーグ・ピラミッド＋昇降格移籍: 組織をまたぐ昇降格（league_transfer）。
--
-- 「九州リーグ ⊃ 大分県リーグ」のようなピラミッドを組織階層（organizations.parent_organization_id ＋
-- OrganizationHierarchyService）から導出し、シーズン後の昇格（下位→上位）・降格（上位→下位）を
-- 「プッシュ＋承認」の対称モデル（DISPATCHED→PLACED/DECLINED/CANCELLED）で行う。
-- 同一大会内の部間昇降格は既存 PromotionService が担当し、本テーブルは関与しない（§2.1）。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/03_league_pyramid_and_transfer.md §3.1
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。team_id / from/to_organization_id /
--     source/target_division_id / initiated_by / responded_by は ID 値のみ保持し、
--     参照整合性はアプリ層で保証する。
--   - from / to の 2 組織をまたぐため単一 organization_id でテナント絞りできない。
--     AbstractTenantAwareRepository は適用せず、用途別 index（from_org / to_org / team）で引く（§3.1）。
--   - initiated_by / responded_by は移籍の証跡として保持＝退会二段モデルの強匿名化対象外（NULL 化しない・§7 / O-4）。

CREATE TABLE league_transfer (
    id                   BINARY(16)  NOT NULL COMMENT 'UUIDv7（原則6）',
    direction            VARCHAR(20) NOT NULL COMMENT 'PROMOTION / RELEGATION',
    team_id              BIGINT      NOT NULL COMMENT '移籍対象チーム（teams.id・FK なし／原則1）。team_id は不変',
    from_organization_id BIGINT      NOT NULL COMMENT '手放す側 org（昇格時=下位県協会 / 降格時=上位協会・FK なし）',
    to_organization_id   BIGINT      NOT NULL COMMENT '受け入れる側 org（昇格時=上位協会 / 降格時=出身県協会・FK なし）',
    source_division_id   BIGINT      NULL     COMMENT '移籍元ディビジョン（tournament_divisions.id・FK なし）',
    target_division_id   BIGINT      NULL     COMMENT '移籍先ディビジョン（承認・配属確定時にセット・FK なし）',
    season               VARCHAR(20) NOT NULL COMMENT 'シーズン識別子（二重起票抑止キーの一部）',
    final_rank           INT         NULL     COMMENT '移籍元での最終順位（昇格枠/降格枠判定の根拠）',
    status               VARCHAR(20) NOT NULL DEFAULT 'DISPATCHED' COMMENT 'DISPATCHED / PLACED / DECLINED / CANCELLED（§3.2）',
    initiated_by         BIGINT      NOT NULL COMMENT '起票者（手放す側 org ADMIN）の user_id（退会時も証跡保持・§7）',
    responded_by         BIGINT      NULL     COMMENT '応答者（受け入れ側 org ADMIN）の user_id（退会時も証跡保持・§7）',
    message              VARCHAR(500) NULL    COMMENT '送り出しメッセージ',
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at         DATETIME    NULL     COMMENT '応答（承認/拒否/取消）日時',

    PRIMARY KEY (id),
    -- 二重起票抑止（§7）
    UNIQUE KEY uq_lt_team_season_direction (team_id, season, direction),
    -- 受信箱（受け入れ側 org が DISPATCHED を引く）
    KEY idx_lt_to_org (to_organization_id, status),
    -- 送り出し側の進捗一覧
    KEY idx_lt_from_org (from_organization_id, status),
    -- チーム側の状況閲覧
    KEY idx_lt_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/03 リーグ移籍（組織をまたぐ昇降格・プッシュ＋承認の対称モデル）';
