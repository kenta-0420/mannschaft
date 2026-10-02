-- F03.11.1 募集キャンセル料の徴収: recruitment_cancellation_records.payment_id への UNIQUE 制約
--
-- 目的（設計書 docs/features/F03.11.1_cancellation_fee_payment.md §7.3）:
--   冪等性防御の第3層（物理的な最後の番人）。Stripe の Idempotency-Key（第1層）と
--   行ロック下の状態再検査（第2層）が万一破れて同一 PaymentIntent/Refund ID を
--   2 件のキャンセル記録に書き込もうとした場合に、DB がこれを拒否して検知可能にする。
--
-- NULL の扱い:
--   payment_id は NULL 許容のままである（未徴収の記録は NOT_REQUIRED/PENDING/FAILED/
--   UNCOLLECTIBLE のいずれかで payment_id が NULL）。MySQL の UNIQUE インデックスは
--   NULL を重複とみなさないため、未徴収の行が何件あっても本制約には抵触しない。
--
-- 既存データへの配慮:
--   本番は未稼働のため移行 SQL は不要（既存実データ無し）。ただし共有開発DBに
--   テスト起因の重複 payment_id が残っている場合、本 ALTER は失敗しうる。
--   その場合は既存データを本マイグレーションで無確認 DELETE することはせず、
--   重複の実態を確認したうえで原因ごとに対処すること（共有開発DBへの無確認DML禁止）。
ALTER TABLE recruitment_cancellation_records
    ADD CONSTRAINT uk_rcr_payment_id UNIQUE (payment_id);
