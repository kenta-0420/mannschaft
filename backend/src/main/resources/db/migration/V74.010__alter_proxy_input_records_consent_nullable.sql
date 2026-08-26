-- F08.9 P3c 後見切替セッションの監査二重記録（03_security §3.2）対応。
--
-- 背景: 後見切替（acting-as）はサーバ側ステートレスで、紙の同意書（proxy_input_consents）を
--       一切伴わない。一方 proxy_input_records の proxy_input_consent_id は NOT NULL + FK のため、
--       「切替開始/終了」の二重記録（audit_logs + proxy_input_records）を書こうとすると
--       FK 制約で書き込み不能になる。
--
-- 是正: proxy_input_consent_id を NULLABLE 化し、後見切替由来の記録（input_source=GUARDIANSHIP_SWITCH）は
--       consent を伴わずに追記できるようにする。既存の紙運用（F14.1）の記録は従来どおり consent_id を持つ。
--       FK（fk_pir_consent）は MySQL では NULL を参照整合性チェックから除外するため、NULL 許可で支障なし。
--       UNIQUE KEY uq_pir_idempotent も NULL を distinct 扱いするため、後見切替記録は重複登録できる
--       （開始/終了が繰り返し起きるため意図どおり）。
--
-- 注意: feature_scope / input_source は VARCHAR で CHECK 制約なし（V18.012）のため、
--       新 enum 値（GUARDIANSHIP_SWITCH）は DDL 変更不要で挿入できる。

ALTER TABLE proxy_input_records
    MODIFY COLUMN proxy_input_consent_id BIGINT UNSIGNED NULL
        COMMENT '根拠となる同意書 FK→proxy_input_consents.id（後見切替=GUARDIANSHIP_SWITCH 由来は NULL）';
