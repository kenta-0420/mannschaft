-- F03.4.1 機能E: メニュー×ライン提供可否 reservation_menu_lines を新設する。
--
-- 提供可否のセマンティクス（確定・入力摩擦ゼロ既定・§3）:
--   行 0 件 = 全ライン（active な全予約対象）で提供可（既定・作成直後は 0 件）
--   行 1 件以上 = 列挙されたラインのみ提供可
--   「全ラインで提供不可」は is_active = FALSE で表現（空配列に意味を持たせない）
--
-- 同一ドメイン（reservation）内の親子のため FK を張る（アーキ原則1・2 に適合。クロスドメインではない）:
--   menu_id → ON DELETE CASCADE 採用: 提供可否はメニューの属性。メニューは論理削除運用だが、
--     テストデータ掃除・GDPR 起点の物理削除時に孤児行を残さない。
--   line_id → CASCADE 不採用（RESTRICT）: ライン削除は「未来予約が存在すれば 409」の論理削除運用
--     （親 §3）であり、提供可否行を黙って消すとメニューの提供範囲が音もなく変わる
--     （暗黙の全ライン化 or 提供先消失）。RESTRICT で物理削除を止め、論理削除時は
--     アプリ層で提供可否行を明示削除する（F03.4.2 §5 のライン削除フロー）。
--
-- 複合自然キー（menu_id × line_id で 1 組 1 行のリレーション表・サロゲート不要 — §11。
-- 行はメニュー上限20×ライン上限20=最大400行/チームでシャーディング負荷にならない）。

CREATE TABLE reservation_menu_lines (
    -- FK → reservation_menus.id（同一ドメイン親子・物理削除時の孤児行防止）。
    menu_id    BINARY(16)      NOT NULL,
    -- FK → reservation_lines.id（同一ドメイン。ラインは論理削除運用のため実質発火しない。
    -- 物理削除を試みた場合の孤児行防止の番人）。
    line_id    BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6)     NOT NULL,

    -- 複合PK（提供関係は1組1行）。
    PRIMARY KEY (menu_id, line_id),
    -- ライン起点の提供メニュー逆引き（F03.4.4 マトリックスUIのフィルター）。
    INDEX idx_rml_line (line_id),
    CONSTRAINT fk_rml_menu FOREIGN KEY (menu_id) REFERENCES reservation_menus (id) ON DELETE CASCADE,
    CONSTRAINT fk_rml_line FOREIGN KEY (line_id) REFERENCES reservation_lines (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='メニュー×ライン提供可否（F03.4.1 機能E・行0件=全ライン提供可・CASCADE/RESTRICT判断は設計書§3）';
