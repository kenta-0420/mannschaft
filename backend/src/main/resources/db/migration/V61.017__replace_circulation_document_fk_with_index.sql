-- F09.14 Phase 3-A: クロスドメイン FK の撤去と index 化
--
-- 背景:
--   V61.010 で disclosure_exports.circulation_document_id に対して
--   circulation_documents(id) への FK (fk_de_circulation) を張っていた。
--   しかし circulation_documents は F05.2（circulation ドメイン）、
--   disclosure_exports は F09.14（disclosure ドメイン）に属する。
--   CLAUDE.md「DB設計の原則 1. クロスドメインFKは作らない」に違反するため撤去する。
--
-- 方針:
--   FK 制約は削除し、参照整合性はアプリケーション層（DisclosureExportService）で保証する。
--   検索性能維持のため index_only に置換する。
--   将来のマイクロサービス分割時にドメイン境界を跨ぐ DB 制約が無いようにする。
ALTER TABLE disclosure_exports
    DROP FOREIGN KEY fk_de_circulation;

-- クロスドメイン FK 禁止のため index-only。整合性はアプリ層で保証する。
CREATE INDEX idx_disclosure_exports_circulation_doc
    ON disclosure_exports(circulation_document_id);
