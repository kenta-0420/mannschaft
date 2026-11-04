-- CMP-260822-1243: 退会の強匿名化（users 物理削除）が失効済み refresh_tokens の FK 違反で完走しない欠陥の根治。
--
-- 原因: refresh_tokens.fk_refresh_tokens_user（V1.003）は users(id) を ON DELETE なしで参照する。
--       退会受付で全トークンが失効済み（revoked_at 付き）になるのに、AccountPurgeService は未失効のみを削除するため、
--       失効済み行が残り、最後の users 物理削除が FK 違反になって TX 全体がロールバックする。
-- 方針: refresh_tokens と users はどちらも auth ドメインのため、同一ドメイン内の ON DELETE CASCADE は許可されている
--       （docs/architecture/domain_db_design_principles.md 原則2）。トークンは users の従属データで、ユーザー削除と運命を共にする。
--       gdpr から auth の Repository を新たに呼ぶ越境依存を作らずに根治できる。
-- 既存データ: 制約の張り替えのみ（行は変更しない）。MySQL 8.0 は1文の ALTER TABLE 内で同名制約の DROP FOREIGN KEY と ADD CONSTRAINT を
--       同時に行える（INPLACE）が、同名制約の削除と再作成を別の文に分け、失敗した文を特定しやすくするため2文にしている（機能上は1文でも可）。
ALTER TABLE refresh_tokens
    DROP FOREIGN KEY fk_refresh_tokens_user;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
