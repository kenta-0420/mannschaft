-- F17.2 Wave2 ⑦村史（行事アーカイブ）: village_event_archives
-- 祭/歳時記/寄合の記録を共通形へ正規化した集約テーブル（案I採用・設計書 §7.2）。
-- 編纂時に summary を焼き付ける「確定した記録（スナップショット）」で、元行事が後日
-- 削除・変更されても村史はぶれない。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §7.2）:
--   - village_id / source_id はいずれも FK非付与の ID 参照のみ（原則1）
--   - (source_type, source_id) UNIQUE で「1行事=1村史エントリ」＝冪等・二重編纂防止（§5.5）
--   - 論理削除（deleted_at）で原則3 に準拠
--   - FESTIVAL 先行実装。CALENDAR_EVENT の年ごと粒度拡張は着手時に別 ALTER（§7.3）

CREATE TABLE village_event_archives (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT '村スコープ（FK非付与・原則1）',
    source_type             VARCHAR(20)     NOT NULL                                COMMENT 'FESTIVAL / CALENDAR_EVENT / MEETUP',
    source_id               BINARY(16)      NOT NULL                                COMMENT '元行事の UUID（ID参照のみ・FK非付与）',
    title                   VARCHAR(200)    NOT NULL                                COMMENT '編纂時に焼き付けた表題',
    summary                 TEXT            NULL                                    COMMENT '編纂サマリ（RSVP集計・実況件数等をテキスト化）',
    thumbnail_r2_key        VARCHAR(255)    NULL                                    COMMENT '代表画像（祭バナー等の複写・任意）',
    archived_at             DATETIME(6)     NOT NULL                                COMMENT '編纂時刻',
    deleted_at              DATETIME(6)     NULL                                    COMMENT '論理削除',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vea_source (source_type, source_id),
    KEY idx_vea_village_archived (village_id, archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村史（行事アーカイブ）（F17.2 Wave2 ⑦）';
