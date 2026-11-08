package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import org.junit.jupiter.api.Tag;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退会時の個人データ削除リスナーと正本表の乖離を防ぐ番人。
 *
 * <p>正本は {@code docs/architecture/withdrawal_flow_immediate_anonymization_fix.md §3}。
 * クラス名の命名規約ではなく、イベント購読メソッドの引数型から全数を列挙する。新しい購読者を
 * 追加したのに正本表を更新し忘れた場合と、表だけが残って実装が消えた場合の両方を失敗させる。</p>
 *
 * <p>CMP-260822-1243 第2陣: 所有列（{@code user_id} / {@code owner_user_id} / {@code *_user_id}）を持つ
 * 全表を Entity と Flyway の両方から列挙し、{@code docs/architecture/account_purge_cross_domain_refactor.md}
 * の個人所有表の判断一覧と照合する。未分類の表と、実装から消えた表の残り行の両方を失敗させる。</p>
 */
@DisplayName("退会個人データ削除リスナー正本整合番人")
@Tag(ArchUnitTestTag.ARCHUNIT)
class WithdrawalPersonalDataListenerCoverageGuardTest {

    private static final Path REPOSITORY_ROOT = findRepositoryRoot();
    private static final Path LEDGER = REPOSITORY_ROOT.resolve(
            "docs/architecture/withdrawal_flow_immediate_anonymization_fix.md");
    private static final String LEDGER_START = "<!-- GDPR_EVENT_LISTENER_LEDGER_START -->";
    private static final String LEDGER_END = "<!-- GDPR_EVENT_LISTENER_LEDGER_END -->";
    private static final String USER_ANONYMIZED_EVENT =
            "com.mannschaft.app.auth.event.UserAnonymizedEvent";
    private static final String ACCOUNT_PURGED_EVENT =
            "com.mannschaft.app.gdpr.event.AccountPurgedEvent";
    private static final Pattern LEDGER_ROW = Pattern.compile(
            "(?m)^\\|\\s*(UserAnonymizedEvent|AccountPurgedEvent)\\s*"
                    + "\\|\\s*`([^`]+)`\\s*\\|");

    @Test
    @DisplayName("UserAnonymizedEvent / AccountPurgedEvent の全購読者が正本表と完全一致する")
    void 全購読者が正本表と完全一致する() throws IOException {
        Set<Subscription> actual = productionSubscriptions();
        Set<Subscription> documented = documentedSubscriptions(Files.readString(LEDGER, StandardCharsets.UTF_8));

        assertCoverage(actual, documented);
    }

    @Test
    @DisplayName("引数型以外のSpring正規購読形式も列挙する")
    void 引数型以外の購読形式も列挙する() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ClassesAttributeFixture.class,
                ComposedAnnotationFixture.class,
                PersonalDataListener.class);

        assertThat(subscriptionsFrom(fixtures))
                .contains(
                        new Subscription("UserAnonymizedEvent", ClassesAttributeFixture.class.getName()),
                        new Subscription("AccountPurgedEvent", ComposedAnnotationFixture.class.getName()));
    }

    @Test
    @DisplayName("不足・余剰・重複・空解析・マーカー欠落を拒否する")
    void 台帳比較の負例を拒否する() {
        Subscription user = new Subscription("UserAnonymizedEvent", "example.UserListener");
        Subscription purge = new Subscription("AccountPurgedEvent", "example.PurgeListener");
        Set<Subscription> actual = Set.of(user, purge);

        assertThatThrownBy(() -> assertCoverage(actual, Set.of(user)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PurgeListener");
        assertThatThrownBy(() -> assertCoverage(actual, Set.of(user, purge,
                        new Subscription("UserAnonymizedEvent", "example.StaleListener"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("StaleListener");
        assertThatThrownBy(() -> assertCoverage(Set.of(), Set.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("空振り");
        assertThatThrownBy(() -> documentedSubscriptions("マーカーなし"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("開始マーカー");

        String duplicateLedger = LEDGER_START + "\n"
                + "| UserAnonymizedEvent | `example.UserListener` | domain | DELETE |\n"
                + "| UserAnonymizedEvent | `example.UserListener` | domain | DELETE |\n"
                + LEDGER_END;
        assertThatThrownBy(() -> documentedSubscriptions(duplicateLedger))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("重複行");
    }

    private static Set<Subscription> productionSubscriptions() {
        return subscriptionsFrom(ProductionClasses.get());
    }

    private static Set<Subscription> subscriptionsFrom(JavaClasses classes) {
        Set<Subscription> subscriptions = new LinkedHashSet<>();
        classes.stream().forEach(javaClass -> {
            javaClass.getMethods().stream()
                    .filter(WithdrawalPersonalDataListenerCoverageGuardTest::isEventListener)
                    .forEach(method -> addMethodSubscriptions(subscriptions, method));
        });
        return subscriptions;
    }

    private static boolean isEventListener(JavaMethod method) {
        return method.isAnnotatedWith(TransactionalEventListener.class)
                || method.isAnnotatedWith(EventListener.class)
                || method.isMetaAnnotatedWith(TransactionalEventListener.class)
                || method.isMetaAnnotatedWith(EventListener.class);
    }

    private static void addMethodSubscriptions(
            Set<Subscription> subscriptions,
            JavaMethod method) {
        Set<String> eventTypeNames = new LinkedHashSet<>();
        method.getRawParameterTypes().forEach(type -> eventTypeNames.add(type.getName()));
        method.getAnnotations().stream()
                .filter(WithdrawalPersonalDataListenerCoverageGuardTest::isEventListenerAnnotation)
                .forEach(annotation -> collectEventDeclarations(
                        annotation, eventTypeNames, new LinkedHashSet<>()));

        addWhenSubscribed(subscriptions, method.getOwner().getName(), eventTypeNames, USER_ANONYMIZED_EVENT);
        addWhenSubscribed(subscriptions, method.getOwner().getName(), eventTypeNames, ACCOUNT_PURGED_EVENT);
    }

    private static void addWhenSubscribed(
            Set<Subscription> subscriptions,
            String listenerFqcn,
            Set<String> eventTypeNames,
            String eventFqcn) {
        if (eventTypeNames.contains(eventFqcn)) {
            subscriptions.add(new Subscription(
                    simpleName(eventFqcn), listenerFqcn));
        }
    }

    private static boolean isEventListenerAnnotation(JavaAnnotation<?> annotation) {
        JavaClass annotationType = annotation.getRawType();
        return annotationType.isEquivalentTo(EventListener.class)
                || annotationType.isEquivalentTo(TransactionalEventListener.class)
                || annotationType.isMetaAnnotatedWith(EventListener.class)
                || annotationType.isMetaAnnotatedWith(TransactionalEventListener.class);
    }

    private static void collectEventDeclarations(
            JavaAnnotation<?> annotation,
            Set<String> classNames,
            Set<String> visitedAnnotationTypes) {
        String annotationTypeName = annotation.getRawType().getName();
        if (!visitedAnnotationTypes.add(annotationTypeName)) {
            return;
        }

        collectDeclaredClassNames(annotation.get("value").orElse(null), classNames);
        collectDeclaredClassNames(annotation.get("classes").orElse(null), classNames);
        annotation.getRawType().getAnnotations().stream()
                .filter(WithdrawalPersonalDataListenerCoverageGuardTest::isEventListenerAnnotation)
                .forEach(metaAnnotation -> collectEventDeclarations(
                        metaAnnotation, classNames, visitedAnnotationTypes));
    }

    private static void collectDeclaredClassNames(Object value, Set<String> classNames) {
        if (value instanceof JavaClass javaClass) {
            classNames.add(javaClass.getName());
        } else if (value instanceof Class<?> reflectedClass) {
            classNames.add(reflectedClass.getName());
        } else if (value instanceof Object[] values) {
            for (Object element : values) {
                collectDeclaredClassNames(element, classNames);
            }
        } else if (value instanceof Iterable<?> values) {
            values.forEach(element -> collectDeclaredClassNames(element, classNames));
        }
    }

    private static void assertCoverage(
            Set<Subscription> actual,
            Set<Subscription> documented) {
        assertThat(actual)
                .as("購読者走査が空振りしていないこと")
                .anyMatch(subscription -> subscription.eventName().equals("UserAnonymizedEvent"))
                .anyMatch(subscription -> subscription.eventName().equals("AccountPurgedEvent"));
        assertThat(documented)
                .as("§3の正本表が空でないこと")
                .isNotEmpty();
        assertThat(documented)
                .as("正本表と実装の購読者が一致すること。差分のFQCNを表または実装へ反映すること")
                .containsExactlyInAnyOrderElementsOf(actual);
    }

    private static Set<Subscription> documentedSubscriptions(String document) {
        int start = document.indexOf(LEDGER_START);
        int end = document.indexOf(LEDGER_END);
        assertThat(start).as("正本表の開始マーカー").isGreaterThanOrEqualTo(0);
        assertThat(end).as("正本表の終了マーカー").isGreaterThan(start);

        String ledger = document.substring(start + LEDGER_START.length(), end);
        Matcher matcher = LEDGER_ROW.matcher(ledger);
        Set<Subscription> subscriptions = new LinkedHashSet<>();
        while (matcher.find()) {
            Subscription subscription = new Subscription(matcher.group(1), matcher.group(2));
            assertThat(subscriptions.add(subscription))
                    .as("正本表に重複行がないこと: %s", subscription)
                    .isTrue();
        }
        return subscriptions;
    }

    private static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("backend/src/main/java"))
                    && Files.isDirectory(current.resolve("docs/architecture"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Mannschaftリポジトリのルートを特定できません");
    }

    // =====================================================================
    // CMP-260822-1243 第2陣: 個人所有表の判断一覧（所有列 user_id / owner_user_id / *_user_id）
    // =====================================================================

    private static final Path OWNER_TABLE_LEDGER = REPOSITORY_ROOT.resolve(
            "docs/architecture/account_purge_cross_domain_refactor.md");
    private static final Path MIGRATION_DIR = REPOSITORY_ROOT.resolve(
            "backend/src/main/resources/db/migration");
    private static final String OWNER_LEDGER_START = "<!-- PERSONAL_OWNER_TABLE_LEDGER_START -->";
    private static final String OWNER_LEDGER_END = "<!-- PERSONAL_OWNER_TABLE_LEDGER_END -->";
    private static final Set<String> OWNER_JUDGMENTS = Set.of("強消去", "匿名化", "弱消去", "保持", "要裁可");
    private static final Pattern OWNER_LEDGER_ROW = Pattern.compile(
            "(?m)^\\|\\s*`(\\w+)`\\s*\\|[^|\\n]*\\|\\s*([^|\\n]+?)\\s*\\|[^|\\n]*\\S[^|\\n]*\\|\\s*$");
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9._]+?)__.*\\.sql$");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\((.*?)\\)\\s*(?:ENGINE|COMMENT|;|DEFAULT)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OWNER_COLUMN_DEFINITION = Pattern.compile(
            "^\\s*`?(\\w*user_id)`?\\s+(?:BIGINT|INT)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?(\\w+)`?(.*?);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_ADD_OWNER_COLUMN = Pattern.compile(
            "ADD\\s+(?:COLUMN\\s+)?`?(\\w*user_id)`?\\s+(?:BIGINT|INT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_RENAME_TO = Pattern.compile(
            "RENAME\\s+TO\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_TABLE = Pattern.compile(
            "RENAME\\s+TABLE\\s+`?(\\w+)`?\\s+TO\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([`\\w,\\s]+);", Pattern.CASE_INSENSITIVE);
    private static final Set<String> OWNER_FIELD_TYPES = Set.of(
            "java.lang.Long", "long", "java.lang.Integer", "int");

    @Test
    @DisplayName("所有列(user_id/owner_user_id/*_user_id)を持つ全表がCMP-260822-1243の判断一覧と完全一致する")
    void 個人所有表が判断一覧と完全一致する() throws IOException {
        Map<String, Set<String>> actual = new TreeMap<>(ownerTablesFromEntities(ProductionClasses.get()));
        ownerTablesFromMigrations(readMigrations()).forEach((table, columns) ->
                actual.computeIfAbsent(table, ignored -> new TreeSet<>()).addAll(columns));
        Map<String, String> documented = documentedOwnerTables(
                Files.readString(OWNER_TABLE_LEDGER, StandardCharsets.UTF_8));

        assertOwnerTableCoverage(actual, documented);
    }

    @Test
    @DisplayName("Flyway の各所有列形式（user_id・owner_user_id・*_user_id・ALTER追加）を検出し、未分類なら赤")
    void Flywayの各所有列形式の未分類表を拒否する() {
        Map<String, String> migrations = new LinkedHashMap<>();
        migrations.put("V1.001__fixture.sql", """
                -- user_id コメント行は列ではない
                CREATE TABLE fixture_plain_owner (
                    id BIGINT NOT NULL,
                    user_id BIGINT UNSIGNED NOT NULL
                ) ENGINE=InnoDB;
                CREATE TABLE IF NOT EXISTS `fixture_owner_column` (
                    `owner_user_id` BIGINT NOT NULL
                );
                CREATE TABLE fixture_actor_column (
                    reviewer_user_id INT NULL
                ) COMMENT='fixture';
                CREATE TABLE fixture_no_owner (id BIGINT NOT NULL);
                CREATE TABLE fixture_dropped (user_id BIGINT NOT NULL);
                CREATE TABLE fixture_old_name (user_id BIGINT NOT NULL);
                """);
        migrations.put("V2.20261031000000__fixture.sql", """
                ALTER TABLE fixture_no_owner ADD COLUMN approver_user_id BIGINT NULL;
                RENAME TABLE fixture_old_name TO fixture_new_name;
                DROP TABLE IF EXISTS fixture_dropped;
                """);

        Map<String, Set<String>> found = ownerTablesFromMigrations(migrations);

        assertThat(found).containsOnlyKeys(
                "fixture_plain_owner", "fixture_owner_column", "fixture_actor_column",
                "fixture_no_owner", "fixture_new_name");
        assertThat(found.get("fixture_owner_column")).containsExactly("owner_user_id");
        assertThat(found.get("fixture_actor_column")).containsExactly("reviewer_user_id");
        assertThat(found.get("fixture_no_owner")).containsExactly("approver_user_id");
        for (String unclassified : found.keySet()) {
            Map<String, String> ledgerWithoutOne = new TreeMap<>();
            found.keySet().stream().filter(table -> !table.equals(unclassified))
                    .forEach(table -> ledgerWithoutOne.put(table, "保持"));
            assertThatThrownBy(() -> assertOwnerTableCoverage(found, ledgerWithoutOne))
                    .as("未分類 %s で赤になること", unclassified)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(unclassified);
        }
    }

    @Test
    @DisplayName("Entity の各所有列形式（userId・ownerUserId・@Column名・継承・@JoinColumn）を検出し、未分類なら赤")
    void Entityの各所有列形式の未分類表を拒否する() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                PlainOwnerEntityFixture.class,
                OwnerUserIdEntityFixture.class,
                NamedColumnEntityFixture.class,
                InheritedOwnerEntityFixture.class,
                OwnerMappedSuperclassFixture.class,
                JoinColumnEntityFixture.class,
                NoOwnerEntityFixture.class);

        Map<String, Set<String>> found = ownerTablesFromEntities(fixtures);

        assertThat(found).containsOnlyKeys(
                "fixture_entity_plain", "fixture_entity_owner", "fixture_entity_named",
                "fixture_entity_inherited", "fixture_entity_join");
        assertThat(found.get("fixture_entity_owner")).containsExactly("owner_user_id");
        assertThat(found.get("fixture_entity_named")).containsExactly("approver_user_id");
        assertThat(found.get("fixture_entity_inherited")).containsExactly("user_id");
        assertThat(found.get("fixture_entity_join")).containsExactly("member_user_id");
        for (String unclassified : found.keySet()) {
            Map<String, String> ledgerWithoutOne = new TreeMap<>();
            found.keySet().stream().filter(table -> !table.equals(unclassified))
                    .forEach(table -> ledgerWithoutOne.put(table, "要裁可"));
            assertThatThrownBy(() -> assertOwnerTableCoverage(found, ledgerWithoutOne))
                    .as("未分類 %s で赤になること", unclassified)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(unclassified);
        }
    }

    @Test
    @DisplayName("判断一覧の余剰行・重複行・不正な判断・空の判断一覧・マーカー欠落を拒否する")
    void 判断一覧の負例を拒否する() {
        Map<String, Set<String>> actual = Map.of("fixture_owner", Set.of("user_id"));

        assertThatThrownBy(() -> assertOwnerTableCoverage(actual,
                Map.of("fixture_owner", "保持", "fixture_stale", "保持")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture_stale");
        assertThatThrownBy(() -> assertOwnerTableCoverage(Map.of(), Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("空振り");
        assertThatThrownBy(() -> documentedOwnerTables("マーカーなし"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("開始マーカー");

        String header = OWNER_LEDGER_START + "\n| 表 | 所有列 | 判断 | 理由・経路 |\n| --- | --- | --- | --- |\n";
        String duplicate = header
                + "| `fixture_owner` | `user_id` | 保持 | 理由 |\n"
                + "| `fixture_owner` | `user_id` | 保持 | 理由 |\n" + OWNER_LEDGER_END;
        assertThatThrownBy(() -> documentedOwnerTables(duplicate))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("重複行");
        String invalidJudgment = header
                + "| `fixture_owner` | `user_id` | 未定 | 理由 |\n" + OWNER_LEDGER_END;
        assertThatThrownBy(() -> documentedOwnerTables(invalidJudgment))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture_owner");
        String malformed = header
                + "| `fixture_owner` | `user_id` | 保持 |\n" + OWNER_LEDGER_END;
        assertThatThrownBy(() -> documentedOwnerTables(malformed))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("解釈できない行");
    }

    private static void assertOwnerTableCoverage(
            Map<String, Set<String>> actual,
            Map<String, String> documented) {
        assertThat(actual)
                .as("所有列を持つ表の走査が空振りしていないこと")
                .isNotEmpty();
        assertThat(documented)
                .as("判断一覧が空でないこと")
                .isNotEmpty();
        Set<String> unclassified = new TreeSet<>(actual.keySet());
        unclassified.removeAll(documented.keySet());
        Set<String> stale = new TreeSet<>(documented.keySet());
        stale.removeAll(actual.keySet());
        assertThat(unclassified)
                .as("所有列を持つのに判断一覧（account_purge_cross_domain_refactor.md）に無い表。"
                        + "強消去・匿名化・保持・要裁可などの判断と理由を1行追加すること: %s", unclassified)
                .isEmpty();
        assertThat(stale)
                .as("判断一覧に残っているが所有列を持つ表として実装に存在しない行: %s", stale)
                .isEmpty();
    }

    private static Map<String, String> documentedOwnerTables(String document) {
        int start = document.indexOf(OWNER_LEDGER_START);
        int end = document.indexOf(OWNER_LEDGER_END);
        assertThat(start).as("判断一覧の開始マーカー").isGreaterThanOrEqualTo(0);
        assertThat(end).as("判断一覧の終了マーカー").isGreaterThan(start);

        Map<String, String> tables = new TreeMap<>();
        for (String line : document.substring(start + OWNER_LEDGER_START.length(), end).split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("| `")) {
                continue;
            }
            Matcher matcher = OWNER_LEDGER_ROW.matcher(trimmed);
            assertThat(matcher.matches()).as("判断一覧の解釈できない行: %s", trimmed).isTrue();
            String table = matcher.group(1);
            String judgment = matcher.group(2);
            assertThat(OWNER_JUDGMENTS)
                    .as("判断一覧 %s の判断は %s のいずれか", table, OWNER_JUDGMENTS)
                    .contains(judgment);
            assertThat(tables.put(table, judgment))
                    .as("判断一覧に重複行がないこと: %s", table)
                    .isNull();
        }
        return tables;
    }

    private static Map<String, Set<String>> ownerTablesFromEntities(JavaClasses classes) {
        Map<String, Set<String>> tables = new TreeMap<>();
        classes.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith("jakarta.persistence.Entity")
                        || javaClass.isAnnotatedWith("jakarta.persistence.Table"))
                .forEach(javaClass -> {
                    String table = annotationName(javaClass.getAnnotations(), "jakarta.persistence.Table")
                            .orElseGet(() -> snakeCase(javaClass.getSimpleName()));
                    Set<String> columns = new TreeSet<>();
                    for (JavaField field : javaClass.getAllFields()) {
                        if (field.getModifiers().contains(JavaModifier.STATIC)) {
                            continue;
                        }
                        Optional<String> joinColumn =
                                annotationName(field.getAnnotations(), "jakarta.persistence.JoinColumn");
                        String column;
                        if (joinColumn.isPresent()) {
                            column = joinColumn.get();
                        } else if (OWNER_FIELD_TYPES.contains(field.getRawType().getName())) {
                            column = annotationName(field.getAnnotations(), "jakarta.persistence.Column")
                                    .orElseGet(() -> snakeCase(field.getName()));
                        } else {
                            continue;
                        }
                        if (isOwnerColumn(column)) {
                            columns.add(column.toLowerCase(Locale.ROOT));
                        }
                    }
                    if (!columns.isEmpty()) {
                        tables.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> new TreeSet<>())
                                .addAll(columns);
                    }
                });
        return tables;
    }

    private static Optional<String> annotationName(
            Set<? extends JavaAnnotation<?>> annotations, String annotationType) {
        return annotations.stream()
                .filter(annotation -> annotation.getRawType().getName().equals(annotationType))
                .findFirst()
                .flatMap(annotation -> annotation.get("name"))
                .map(Object::toString)
                .filter(name -> !name.isBlank());
    }

    private static boolean isOwnerColumn(String column) {
        String normalized = column.toLowerCase(Locale.ROOT);
        return normalized.equals("user_id") || normalized.endsWith("_user_id");
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> readMigrations() throws IOException {
        Map<String, String> migrations = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (MIGRATION_NAME.matcher(name).matches()) {
                    migrations.put(name, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(migrations).as("Flyway migration の走査が空振りしていないこと").isNotEmpty();
        return migrations;
    }

    /** Flyway を版順に読み、最終 schema で所有列を持つ表を返す（CREATE / ALTER ADD / RENAME / DROP を反映）。 */
    private static Map<String, Set<String>> ownerTablesFromMigrations(Map<String, String> migrations) {
        List<String> ordered = new ArrayList<>(migrations.keySet());
        ordered.sort(Comparator.comparing(
                WithdrawalPersonalDataListenerCoverageGuardTest::migrationVersion,
                WithdrawalPersonalDataListenerCoverageGuardTest::compareVersions));
        Map<String, Set<String>> tables = new HashMap<>();
        for (String name : ordered) {
            String sql = SQL_LINE_COMMENT.matcher(migrations.get(name)).replaceAll("");
            Matcher create = CREATE_TABLE.matcher(sql);
            while (create.find()) {
                Set<String> columns = new TreeSet<>();
                Matcher column = OWNER_COLUMN_DEFINITION.matcher(create.group(2));
                while (column.find()) {
                    columns.add(column.group(1).toLowerCase(Locale.ROOT));
                }
                tables.put(create.group(1).toLowerCase(Locale.ROOT), columns);
            }
            Matcher alter = ALTER_TABLE.matcher(sql);
            while (alter.find()) {
                String table = alter.group(1).toLowerCase(Locale.ROOT);
                Matcher added = ALTER_ADD_OWNER_COLUMN.matcher(alter.group(2));
                while (added.find()) {
                    tables.computeIfAbsent(table, ignored -> new TreeSet<>())
                            .add(added.group(1).toLowerCase(Locale.ROOT));
                }
                Matcher renamed = ALTER_RENAME_TO.matcher(alter.group(2));
                while (renamed.find()) {
                    if (tables.containsKey(table)) {
                        tables.put(renamed.group(1).toLowerCase(Locale.ROOT), tables.remove(table));
                    }
                }
            }
            Matcher rename = RENAME_TABLE.matcher(sql);
            while (rename.find()) {
                String from = rename.group(1).toLowerCase(Locale.ROOT);
                if (tables.containsKey(from)) {
                    tables.put(rename.group(2).toLowerCase(Locale.ROOT), tables.remove(from));
                }
            }
            Matcher drop = DROP_TABLE.matcher(sql);
            while (drop.find()) {
                for (String table : drop.group(1).split("\\s*,\\s*")) {
                    tables.remove(table.replace("`", "").strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, Set<String>> owned = new TreeMap<>();
        tables.forEach((table, columns) -> {
            if (!columns.isEmpty()) {
                owned.put(table, columns);
            }
        });
        return owned;
    }

    private static List<Long> migrationVersion(String fileName) {
        Matcher matcher = MIGRATION_NAME.matcher(fileName);
        assertThat(matcher.matches()).as("Flyway 版の形式: %s", fileName).isTrue();
        List<Long> parts = new ArrayList<>();
        for (String part : matcher.group(1).split("[._]")) {
            parts.add(Long.parseLong(part));
        }
        return parts;
    }

    private static int compareVersions(List<Long> left, List<Long> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int compared = Long.compare(left.get(i), right.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    @jakarta.persistence.Table(name = "fixture_entity_plain")
    private static class PlainOwnerEntityFixture {
        private Long id;
        private Long userId;
    }

    @jakarta.persistence.Table(name = "fixture_entity_owner")
    private static class OwnerUserIdEntityFixture {
        private Long ownerUserId;
        private String ownerName;
    }

    @jakarta.persistence.Table(name = "fixture_entity_named")
    private static class NamedColumnEntityFixture {
        @jakarta.persistence.Column(name = "approver_user_id")
        private Long approver;
    }

    private abstract static class OwnerMappedSuperclassFixture {
        private Long userId;
    }

    @jakarta.persistence.Table(name = "fixture_entity_inherited")
    private static class InheritedOwnerEntityFixture extends OwnerMappedSuperclassFixture {
        private String label;
    }

    @jakarta.persistence.Table(name = "fixture_entity_join")
    private static class JoinColumnEntityFixture {
        @jakarta.persistence.JoinColumn(name = "member_user_id")
        private Object member;
    }

    @jakarta.persistence.Table(name = "fixture_entity_none")
    private static class NoOwnerEntityFixture {
        private Long teamId;
        private String userName;
    }

    private record Subscription(String eventName, String listenerFqcn) {
    }

    private static class ClassesAttributeFixture {

        @EventListener(UserAnonymizedEvent.class)
        void onAnonymized() {
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @EventListener(AccountPurgedEvent.class)
    private @interface PersonalDataListener {
    }

    private static class ComposedAnnotationFixture {

        @PersonalDataListener
        void onPurged() {
        }
    }

}
