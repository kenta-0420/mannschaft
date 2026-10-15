-- F08.8 Phase 1: 理事任期独立テーブル
-- board_handover_packs (同ドメイン) とは FK 許容、user_id は users.id (クロスドメイン) のため FK なし。
CREATE TABLE team_member_terms (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL, -- users.id（FKなし）
    role_label VARCHAR(60) NOT NULL,
    term_start DATE NOT NULL,
    term_end DATE NOT NULL,
    handover_pack_id BINARY(16) NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_tmt_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM'))
    -- handover_pack_id への FK は V13.050 (board_handover_packs) 作成後の V13.051+ では張れるが、
    -- 循環依存（pack -> term の参照も論理的にあるため）を避け、本機能は FK 制約なしの ID 参照とする
);

CREATE INDEX idx_tmt_organization_id ON team_member_terms (organization_id);
CREATE INDEX idx_tmt_scope_user ON team_member_terms (scope_type, scope_id, user_id);
CREATE INDEX idx_tmt_active ON team_member_terms (scope_type, scope_id, is_active, term_end);
CREATE INDEX idx_tmt_term_end ON team_member_terms (term_end);
CREATE INDEX idx_tmt_user ON team_member_terms (user_id, term_start, term_end);
CREATE INDEX idx_tmt_handover_pack ON team_member_terms (handover_pack_id);
