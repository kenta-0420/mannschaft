-- F22.1 市（Market）謝礼決済 §6.3 検分🔴根治: RECOVERY 仕訳の 4 経路を識別する discriminator 列。
--
-- 背景（消失バグの機序）:
--   RECOVERY×PAYEE の仕訳は 3 種が同一 escrow 上に同居しうるが、勘定の向き（D/C）だけでは峻別できない:
--     ・C1 発生計上（recordModeBStripeFeeRecovery）       = D PLATFORM_FEE / C PAYEE（その escrow 自身の実 Stripe 手数料）
--     ・C2 補完（completePendingRecovery）                 = D PLATFORM_FEE / C PAYEE（C1 と同一会計・pending の後追い）
--     ・A 回収実行（recordRecoveryExecution）              = D PAYEE / C PLATFORM_FEE（他者債務を当該 charge に上乗せ回収）
--     ・A 再計上（recapitalizeAppliedRecoveryOnRefund）     = D PLATFORM_FEE / C PAYEE（A 回収を ModeB 返金で打ち消す逆仕訳）
--   「A 回収を上乗せした charge が自己 ModeB 返金される」自己返金時、同一 refund 処理内で C1（C PAYEE）と
--   A 再計上（C PAYEE）が同居する。回収済み純額を D−C で読む sumAppliedRecoveryNetOnEscrow が C1 の C PAYEE を
--   混入して純額を過小評価し（回収 D − C1 の C ≤ 0）、A 再計上が早期 return → 回収済み金が outstanding に戻らず消失する。
--
-- 根治: RECOVERY 仕訳に明示の識別（recovery_kind）を持たせ、「当該 escrow に上乗せ適用した回収の純額」を
--   A 経路（C1_ACCRUAL/C2_COMPLETION を除外）だけで導出する。勘定の向きだけに依存しない確実な峻別とする。
--
-- 既存データ非破壊: 既存 RECOVERY 行（V84.002 投入後の本ブランチ内データ）には recovery_kind が無いが、
--   本機構は本 PR で初導入のため運用環境に既存 RECOVERY 行は存在しない（V84.001/002 と同一リリース）。
--   NULL 許容で追加し、CHECK は「RECOVERY 以外は NULL・RECOVERY は 4 値のいずれか（または後方互換で NULL）」を許す。
--   非 RECOVERY 行（AUTHORIZE/CAPTURE/TRANSFER_OUT/FEE/REFUND/CANCEL）は recovery_kind=NULL のまま。
--
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.3 / 02_api_design.md §6.3
ALTER TABLE ledger_entries
    ADD COLUMN recovery_kind VARCHAR(16) NULL
        COMMENT 'RECOVERY 仕訳の経路識別（C1_ACCRUAL/C2_COMPLETION/A_EXECUTION/A_RECAPITALIZE・非 RECOVERY は NULL）'
        AFTER stripe_object_id;

-- 非 RECOVERY 行は recovery_kind=NULL を強制し、RECOVERY 行は 4 値のいずれか（後方互換で NULL も許容）に限定する。
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_le_recovery_kind CHECK (
        (entry_type = 'RECOVERY'
            AND (recovery_kind IS NULL
                 OR recovery_kind IN ('C1_ACCRUAL','C2_COMPLETION','A_EXECUTION','A_RECAPITALIZE')))
        OR (entry_type <> 'RECOVERY' AND recovery_kind IS NULL));
