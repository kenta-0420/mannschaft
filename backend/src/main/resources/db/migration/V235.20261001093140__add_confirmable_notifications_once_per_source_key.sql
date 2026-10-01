-- =====================================================================
-- CMP-260930-1932: 募集の自動キャンセル通知を「募集1件につき確認通知1件」に DB レベルで制限する
-- =====================================================================
-- 自動キャンセル通知は業務TXのコミット後に AFTER_COMMIT + @Async のリスナー
-- （RecruitmentAutoCancelledNotificationListener）で送る。リスナーは existsBySourceTypeAndSourceId で
-- 送信済みを確認するが、並行2回発火では両方が確認をすり抜けうるため、後着側の INSERT を一意制約で拒否する。
--
-- (source_type, source_id) 全体への UNIQUE は不可: MARKET_FINALIZE は再 FULL のたびに同じ札へ
-- 最終認証を再送する仕様であり、同一 (source_type, source_id) の行が複数できる。
-- そこで「1回限り」の発生元種別のときだけ非 NULL になる生成列 + UNIQUE KEY で表現する
-- （MySQL は UNIQUE で NULL を複数許容するため、他の種別の行は制約の対象外。V227 の
-- future_reservation_key と同型）。
--
-- 既存データ: 旧バッチは source_type を指定せず既定値 EMERGENCY_CLOSURE で送っていたため、
-- source_type='RECRUITMENT_AUTO_CANCEL' の既存行は無い（UNIQUE 追加は既存行で失敗しない）。
-- =====================================================================

ALTER TABLE confirmable_notifications
    ADD COLUMN once_per_source_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN source_type IN ('RECRUITMENT_AUTO_CANCEL') AND source_id IS NOT NULL
                 THEN CONCAT(source_type, '|', source_id)
                 ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uq_cn_once_per_source (once_per_source_key);
