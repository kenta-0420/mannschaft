-- CMP-260920-0705: content_reports.content_hidden 列の欠落を根治する。
-- ContentReportEntity は contentHidden（@Column(nullable = false)・既定 false）を持ち
-- hideContent() / unhideContent() で更新されるが、この列を追加する migration が存在しなかった。
-- そのため content_reports を SELECT する全クエリが
-- Unknown column 'cre1_0.content_hidden'（SQLState 42S22）で落ち、
-- 運営の通報一覧 GET /api/v1/admin/moderation/reports が常時 HTTP 500 になっていた。
-- V10.001 で reported_by / scope_type / content_snapshot / reviewed_at / updated_at を
-- 一括追加した際の単純な追加漏れであり、設計上の食い違いではない。
--
-- DEFAULT FALSE を伴うため既存行があっても安全に適用できる（既存行は自動的に 0 が入る）。

ALTER TABLE content_reports
    ADD COLUMN content_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '通報対象コンテンツを非表示にしたか（モデレーション操作の結果）' AFTER reviewed_at;
