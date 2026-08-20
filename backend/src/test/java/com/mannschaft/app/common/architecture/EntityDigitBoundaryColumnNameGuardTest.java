package com.mannschaft.app.common.architecture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>数字→大文字境界を含む Entity フィールドは {@code @Column(name=...)} を明示すべき</b> 番人テスト。
 *
 * <h2>守る不変条件</h2>
 * <p>Spring Boot の既定物理命名戦略（{@code CamelCaseToUnderscoresNamingStrategy}）は
 * 「小文字の直後の大文字」にのみ区切りを入れる。<b>数字の直後の大文字には区切りを入れない</b>ため、
 * {@code s3Key} → {@code s3key}、{@code r2ObjectKey} → {@code r2object_key} となる。
 * 一方 Flyway の DDL は人間が書くため {@code s3_key} / {@code r2_object_key} となっており、
 * Entity 側で {@code @Column(name=...)} を省略すると<b>物理列名が食い違い、当該テーブルを
 * 触るクエリが実 DB で必ず失敗する</b>（Issue #2856 / data_exports.s3_key）。</p>
 *
 * <h2>なぜ通常のテストで検出できないか</h2>
 * <p>{@code test} プロファイルは {@code ddl-auto=create}（Entity 由来 DDL）でスキーマを作るため、
 * Entity とマイグレーションの食い違いは自己整合して必ず緑になる。よって
 * 「命名戦略が曖昧になる境界では列名を明示する」という静的規約で根治する。</p>
 *
 * <p>実 Flyway スキーマに対する経験的な再現は
 * {@code com.mannschaft.app.common.schema.EntityDigitBoundaryColumnFlywaySchemaIT} が担う。
 * 本テストは Docker 不要で全 Entity を機械走査する第一防衛線である。</p>
 */
@DisplayName("Entity 数字→大文字境界フィールドの @Column(name) 明示 番人テスト")
class EntityDigitBoundaryColumnNameGuardTest {

    private static final String BASE_PACKAGE = "com.mannschaft.app";

    @Test
    @DisplayName("数字の直後に大文字が続くフィールドは @Column(name=...) を明示している")
    void digitBoundaryFieldsDeclareExplicitColumnName() throws ClassNotFoundException {
        List<String> violations = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> entityClass = Class.forName(candidate.getBeanClassName());
            for (Field field : entityClass.getDeclaredFields()) {
                if (field.isSynthetic()
                        || Modifier.isStatic(field.getModifiers())
                        || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                if (!hasDigitUpperCaseBoundary(field.getName())) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.name().isBlank()) {
                    violations.add(entityClass.getName() + "#" + field.getName());
                }
            }
        }

        assertThat(violations)
                .as("数字の直後に大文字が続くフィールドは物理命名戦略が区切りを入れないため、"
                        + "@Column(name=\"...\") で Flyway の実列名を明示しなければならない（Issue #2856）")
                .isEmpty();
    }

    /** {@code s3Key} の {@code 3K} のように「数字→大文字」の並びを含むか。 */
    private static boolean hasDigitUpperCaseBoundary(String name) {
        for (int i = 1; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i - 1)) && Character.isUpperCase(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
