-- F05.5 ファイル共有セキュリティ強化 C: ダウンロード禁止フラグ
--
-- shared_folders / shared_files に download_disabled 列を追加する。
--   - BOOLEAN NOT NULL DEFAULT FALSE（既定 = DL 可・従来挙動・AC-C5 非回帰）。
--   - 実効禁止 = フォルダ.download_disabled OR ファイル.download_disabled（禁止は単調・ファイルで解除不可）。
--   - presignDownload（DL URL 発行）でのみ評価し、閲覧（メタ／一覧／詳細）は通す。
--
-- 【設計上の限界】ブラウザで表示できる以上、完全なダウンロード防止は原理的に不可能。
-- 本フラグは DL ボタン抑止＋DL URL 発行拒否による運用上の抑止であり、完全防止ではない。
--
-- 原則準拠: 列追加のみ・DEFAULT FALSE ゆえ既存行は全て従来どおり DL 可（非回帰）。
-- from-scratch でも適用済み環境でも壊れない（DEFAULT 付きの純追加）。

ALTER TABLE shared_folders
    ADD COLUMN download_disabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'C: DL 禁止フラグ（true=配下ファイルの DL URL 発行を拒否。禁止は単調・ファイルで解除不可）'
        AFTER min_visible_role;

ALTER TABLE shared_files
    ADD COLUMN download_disabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'C: ファイル個別の DL 禁止フラグ（実効禁止=フォルダ OR ファイル）'
        AFTER min_visible_role;
