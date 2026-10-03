-- CMP-260920-0705 の Codex 検分（PR #3396）で指摘された残件を根治する。
-- 設計書（docs/features/F10.1_admin_dashboard.md）は content_reports_archive を
-- content_reports と同一スキーマと定義しているが、V220（本体への content_hidden 追加）は
-- アーカイブ表に対を作らずに終わっていた。
-- V10.001（本体）と V10.023（アーカイブ）はこれまで同じ列を同じ順序で追加・削除しており対称だったため、
-- 今回もその対称性を保つ。アーカイブへの月次移行バッチは本 PR 時点で未実装（backend/src/main/java に
-- content_reports_archive を参照するコードは存在しない）ため、現時点で移行失敗は起きないが、
-- 将来バッチを実装した際に列数不一致で INSERT ... SELECT が失敗する地雷になるため先に塞いでおく。

ALTER TABLE content_reports_archive
    ADD COLUMN content_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '通報対象コンテンツを非表示にしたか（モデレーション操作の結果、本体V220と対）' AFTER reviewed_at;
