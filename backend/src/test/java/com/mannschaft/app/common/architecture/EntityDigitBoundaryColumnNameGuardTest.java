package com.mannschaft.app.common.architecture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>物理命名戦略が「人が DDL に書く形」と食い違うフィールドは {@code @Column(name=...)} を
 * 明示すべき</b> 番人テスト（Issue #2856）。
 *
 * <h2>守る不変条件</h2>
 * <p>Spring Boot の既定物理命名戦略（Hibernate の {@code CamelCaseToUnderscoresNamingStrategy}）は
 * <b>「小文字 → 大文字 → 小文字」の並びでしか {@code _} を挿入しない</b>。したがって:</p>
 * <ul>
 *   <li>{@code s3Key} → {@code s3key}（数字の直後の大文字では切らない。実列 {@code s3_key}）</li>
 *   <li>{@code r2ObjectKey} → {@code r2object_key}（実列 {@code r2_object_key}）</li>
 *   <li>{@code alertSent30d} → {@code alert_sent30d}（小文字→数字では切らない。実列 {@code alert_sent_30d}）</li>
 *   <li>{@code positionX} → {@code positionx}（末尾の大文字では切らない。実列 {@code position_x}）</li>
 * </ul>
 * <p>いずれも Flyway の実列名と食い違い、<b>実 DB で当該テーブルを触るクエリが必ず
 * {@code Unknown column} で失敗</b>する。</p>
 *
 * <h2>判定の仕方（DDL を推測しない）</h2>
 * <p>本テストは「正しい列名」を推測しない。命名戦略の出力
 * （{@link #physicalNameByNamingStrategy}）と、<b>文字種の境界すべてで区切った形</b>
 * （{@link #fullySplitName}・曖昧さの検出器としてのみ使う）を比べ、<b>両者が食い違う
 * フィールド＝人間の DDL 表記と機械の導出が一致する保証が無いフィールド</b>を
 * 「{@code @Column(name=...)} 必須」と判定する。{@code userId} → {@code user_id} のように
 * 両者が一致する素直なフィールドは対象外であり、既存の大多数の Entity には影響しない。</p>
 *
 * <h2>なぜ通常のテストで検出できないか</h2>
 * <p>{@code test} プロファイルは {@code ddl-auto=create}（Entity 由来 DDL）でスキーマを作るため、
 * Entity とマイグレーションの食い違いは自己整合して必ず緑になる。</p>
 *
 * <h2>この番人が守らない範囲（網羅性の誤認防止）</h2>
 * <p>本テストが見るのは <b>{@code @Entity} クラス自身が宣言する非 static・非 {@code @Transient}
 * フィールドの列名だけ</b>である。次は対象外であり、別の手段（下記の実スキーマ結合テストや
 * {@code FlywayFromScratchMigrationTest} の包括ドリフト検査）に委ねている:</p>
 * <ul>
 *   <li>{@code @MappedSuperclass} / {@code @Embeddable} が宣言するフィールド（継承・埋め込み先では見ない）</li>
 *   <li>{@code @JoinColumn(name=...)} / {@code @CollectionTable} / {@code @SecondaryTable} の列名</li>
 *   <li>{@code @AttributeOverride} による列名の上書き</li>
 *   <li>{@code @OrderBy} や JPQL / ネイティブクエリ内に文字列で書かれた列名</li>
 *   <li>「列名が存在するか」そのもの（本テストは綴りの曖昧さだけを見る。実在確認は
 *       {@code com.mannschaft.app.common.schema.EntityDigitBoundaryColumnFlywaySchemaIT} と
 *       {@code FlywayFromScratchMigrationTest} が実スキーマに対して行う）</li>
 * </ul>
 */
@DisplayName("Entity 物理列名の曖昧フィールドは @Column(name) 明示 番人テスト")
class EntityDigitBoundaryColumnNameGuardTest {

    private static final String BASE_PACKAGE = "com.mannschaft.app";

    /**
     * 走査が空振りしていないことを保証する下限。
     * 2026-08-20 実測で {@code @Entity} は 464 件。パッケージ改名・クラスパス構成変更・
     * スキャナ挙動変更で 0 件走査になっても「違反なし＝緑」で静かに無力化されるのを防ぐ。
     * 実測値そのままだと Entity 削除で落ちるため、実測の 8 割強を下限に置く。
     */
    private static final int MIN_SCANNED_ENTITIES = 380;

    @Test
    @DisplayName("命名戦略の導出が曖昧なフィールドは @Column(name=...) を明示している")
    void ambiguousFieldsDeclareExplicitColumnName() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(BASE_PACKAGE);

        assertThat(candidates)
                .as("@Entity の走査が空振りしていないこと（0 件でも violations は空になり、"
                        + "本テストが静かに無力化されるため下限を明示する）")
                .hasSizeGreaterThan(MIN_SCANNED_ENTITIES);

        List<String> violations = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            Class<?> entityClass = Class.forName(candidate.getBeanClassName());
            for (Field field : entityClass.getDeclaredFields()) {
                if (field.isSynthetic()
                        || Modifier.isStatic(field.getModifiers())
                        || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                if (!isAmbiguous(field.getName())) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.name().isBlank()) {
                    violations.add(entityClass.getName() + "#" + field.getName()
                            + "（命名戦略の導出: " + physicalNameByNamingStrategy(field.getName()) + "）");
                }
            }
        }

        assertThat(violations)
                .as("物理命名戦略の導出が人間の DDL 表記と一致する保証が無いフィールドは、"
                        + "@Column(name=\"...\") で Flyway の実列名を明示しなければならない（Issue #2856）")
                .isEmpty();
    }

    /**
     * 命名戦略の導出と、文字種境界すべてで区切った形が食い違うか。
     * 食い違う＝どちらが実列名かはフィールド名だけからは決まらない＝明示が必要。
     */
    private static boolean isAmbiguous(String fieldName) {
        return !physicalNameByNamingStrategy(fieldName).equals(fullySplitName(fieldName));
    }

    /**
     * Hibernate {@code CamelCaseToUnderscoresNamingStrategy} と同じ規則で物理名を導出する
     * （「小文字 → 大文字 → 小文字」の並びでのみ {@code _} を挿入し、最後に小文字化）。
     */
    static String physicalNameByNamingStrategy(String fieldName) {
        StringBuilder buf = new StringBuilder(fieldName.replace('.', '_'));
        for (int i = 1; i < buf.length() - 1; i++) {
            if (Character.isLowerCase(buf.charAt(i - 1))
                    && Character.isUpperCase(buf.charAt(i))
                    && Character.isLowerCase(buf.charAt(i + 1))) {
                buf.insert(i++, '_');
            }
        }
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 文字種（小文字 / 大文字 / 数字）の境界すべてで区切った形。
     * 「正しい列名」ではなく、命名戦略の導出が曖昧かどうかを測るための基準値として使う。
     */
    static String fullySplitName(String fieldName) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (i > 0 && needsBoundary(fieldName.charAt(i - 1), c)) {
                buf.append('_');
            }
            buf.append(c);
        }
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean needsBoundary(char prev, char cur) {
        if (prev == '_' || cur == '_') {
            return false;
        }
        boolean classChanged = Character.isDigit(prev) != Character.isDigit(cur)
                || (!Character.isDigit(prev) && Character.isLowerCase(prev) && Character.isUpperCase(cur));
        return classChanged;
    }
}
