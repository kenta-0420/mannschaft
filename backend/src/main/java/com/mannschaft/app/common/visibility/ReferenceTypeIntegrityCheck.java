package com.mannschaft.app.common.visibility;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 起動時に {@code corkboard_card_reference.reference_type} の DB 値と
 * {@link ReferenceType} enum の差分を検出するヘルスチェック。
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §11.2 / §15 D-12 完全一致。
 * DB 直接挿入や旧バージョン由来の不明 type が残っている場合に WARN ログを出すことで、
 * cardinality 爆発・fail-closed による「過去ピン全部見えない」現象の検出を可能にする。
 *
 * <p>テーブル不在時・DB 接続不能時はアプリ起動を阻害しない (fail-open 起動)。
 *
 * <p>{@link VisibilityMetrics} 経由で {@code content_visibility.unsupported_reference_type} を
 * 増加させるかは本クラスの責務外 (実行時に未対応 type へ到達した時に Checker が記録する)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReferenceTypeIntegrityCheck {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 起動時 (Spring Bean 初期化完了直後) に DB 上の distinct {@code reference_type} 値と
     * {@link ReferenceType} enum 定義値を比較する。
     *
     * <p>差分があれば WARN ログを出力するが、起動は阻害しない。
     */
    @PostConstruct
    public void verifyOnStartup() {
        try {
            List<String> dbValues = jdbcTemplate.queryForList(
                "SELECT DISTINCT reference_type FROM corkboard_card_reference "
                    + "WHERE reference_type IS NOT NULL",
                String.class);
            Set<String> enumValues = Arrays.stream(ReferenceType.values())
                .map(ReferenceType::name)
                .collect(Collectors.toSet());

            Set<String> unknown = new HashSet<>(dbValues);
            unknown.removeAll(enumValues);

            if (!unknown.isEmpty()) {
                log.warn(
                    "ReferenceTypeIntegrityCheck: DB に enum 未定義の reference_type {} 件発見: {} "
                        + "(設計書 §15 D-12 — 値の削除は禁止、deprecated 化のみ。"
                        + "fail-closed により当該データへの canView は false になる)",
                    unknown.size(), unknown);
            } else {
                log.info(
                    "ReferenceTypeIntegrityCheck: DB 上の reference_type {} 件すべて enum 定義と一致",
                    dbValues.size());
            }
        } catch (BadSqlGrammarException e) {
            // テーブル不在 (テスト環境等) は fail-open
            log.info(
                "ReferenceTypeIntegrityCheck: corkboard_card_reference テーブル不在 — スキップ");
        } catch (RuntimeException e) {
            // DB 接続不能等は fail-open
            log.warn(
                "ReferenceTypeIntegrityCheck: 起動時チェックに失敗 (起動は継続): {}",
                e.getMessage());
        }
    }
}
