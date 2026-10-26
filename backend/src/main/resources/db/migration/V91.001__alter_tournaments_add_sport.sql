-- F08.10 多競技対応（🟡-1a）: 大会（tournaments）に競技種別 sport 列を追加する。
--
-- 背景:
--   大会の直接スコア入力（系統B・FixtureService#recordMatchCanonical→
--   MatchService#recordTournamentScore）で作る canonical match は、これまで sport=SOCCER 固定だった。
--   多競技大会（バレー/将棋/囲碁/バスケ/フットサル等）でも誤って SOCCER の正本 match を作ってしまうため、
--   大会自身に競技種別を持たせ、canonical match の sport をその値に従わせる。
--
-- 列定義:
--   tournaments.sport VARCHAR(32) NOT NULL DEFAULT 'SOCCER'
--     match.domain.Sport の列挙名を文字列で保持する
--     （SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO/FIGURE_SKATING/GYMNASTICS）。
--     match ドメインの enum を tournaments に型結合させず String 保存することで、
--     モジュラーモノリスのドメイン境界（CLAUDE.md ドメイン境界の原則）を侵さない。
--     値の妥当性は DTO の @Pattern と Service の Sport.valueOf 相当で担保する。
--
-- 既存データ移行（feedback_flyway_existing_data_check_drop 観点）:
--   グリーンフィールド（既存 tournaments は全てサッカー前提）のため、既存大会は DEFAULT 'SOCCER' で
--   後方互換に充填される（追加の UPDATE 不要）。SOCCER=従来挙動と一致するため canonical match も従来どおり。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max）:
--   tournaments CREATE は V8.038 系。本 ALTER は origin/main 全体最大 major（V90 系）の次として V91.001 を採る。
--   V91 は V8.038 含む全先行マイグレーションより後にソートされるため、from-scratch でも
--   tournaments 生成後に本 ALTER が走る正しい順序が保証される。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避・
--     feedback_migration_version_collision）。
--
-- 設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1（Sport）

ALTER TABLE tournaments
    ADD COLUMN sport VARCHAR(32) NOT NULL DEFAULT 'SOCCER'
        COMMENT '競技種別（match.domain.Sport の列挙名・既定 SOCCER・F08.10 多競技対応）'
        AFTER format;
