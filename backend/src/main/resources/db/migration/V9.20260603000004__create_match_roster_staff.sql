-- F08.7.1 / 05 試合メンバー表（項目拡充）: ベンチ入り役員（監督・コーチ・トレーナー等）。
--
-- 選手以外のベンチ入り役員を試合単位×参加チーム単位で記載する。アプリ未登録者（協会派遣の帯同審判等）も
-- name/role だけで記載できるよう user_id は NULL 可。
--
-- 原則準拠:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - match_id / participant_id は同一 tournament ドメイン内テーブル（tournament_matches /
--     tournament_participants = BIGINT PK）への ID 参照ゆえ BIGINT。
--   - user_id は user ドメインへの ID 参照。いずれもクロスドメイン FK なし（原則1）。
--
-- 編集権限は選手 roster と同一（自チーム ADMIN/DEPUTY のみ）。締切（roster_deadline）後ロックも同様。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §8.3 / §8.6

CREATE TABLE match_roster_staff (
    id             BINARY(16)   NOT NULL COMMENT 'UUIDv7（原則6）',
    match_id       BIGINT       NOT NULL COMMENT 'tournament_matches.id への ID 参照（同一 tournament ドメイン・FK なし）',
    participant_id BIGINT       NOT NULL COMMENT 'tournament_participants.id への ID 参照（自チーム分・FK なし）',
    role           VARCHAR(32)  NOT NULL COMMENT '役職（監督/コーチ/トレーナー/帯同審判 等。アプリ層で許容値検証）',
    name           VARCHAR(128) NOT NULL COMMENT '氏名（アプリ未登録者も記載可のため文字列で保持）',
    user_id        BIGINT       NULL     COMMENT '紐付くユーザー（user ドメインへの ID 参照・FK なし／原則1・NULL 可）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_match_roster_staff_match (match_id, participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/05 ベンチ入り役員（試合×参加チーム単位）';
