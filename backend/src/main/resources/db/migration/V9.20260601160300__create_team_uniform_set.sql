-- F08.7.1 / 05 試合メンバー表（項目拡充）: ユニフォームセット（チーム単位の色テンプレ）。
--
-- メンバー表提出時に「どのユニフォームセットを着用するか」を指定する。相手チームとのカラー衝突回避のため、
-- 試合ごとに使用セットを上書きできる（roster.uniform_set_id）。セット自体は team_id スコープで再利用する。
--
-- 原則準拠:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - team_id は team ドメインへの ID 参照のみ（クロスドメイン FK なし／原則1）。
--   - soft delete（deleted_at）でテンプレ削除しても過去試合の参照を壊さない（原則3）。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §8.2 / §8.6

CREATE TABLE team_uniform_set (
    id           BINARY(16)  NOT NULL COMMENT 'UUIDv7（原則6）',
    team_id      BIGINT      NOT NULL COMMENT 'team ドメインへの ID 参照（teams.id・FK なし／原則1）',
    kind         VARCHAR(16) NOT NULL COMMENT 'FP=フィールドプレイヤー / GK_PRIMARY=GK正 / GK_SECONDARY=GK副',
    label        VARCHAR(64) NULL     COMMENT '表示名（例「ホーム白」）',
    shirt_color  VARCHAR(32) NOT NULL COMMENT 'シャツ色（色名 or HEX を文字列で保持）',
    shorts_color VARCHAR(32) NOT NULL COMMENT 'パンツ色',
    socks_color  VARCHAR(32) NOT NULL COMMENT 'ソックス色',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME    NULL     COMMENT '論理削除（再利用テンプレの履歴保持）',

    PRIMARY KEY (id),
    -- 同一チーム・同一 kind は複数セット保持可（ホーム/アウェイ等）。一意制約は設けず label で識別
    KEY idx_team_uniform_set_team (team_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/05 ユニフォームセット（チーム単位の色テンプレ）';
