package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
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
 */
@DisplayName("退会個人データ削除リスナー正本整合番人")
class WithdrawalPersonalDataListenerCoverageGuardTest {

    private static final Path REPOSITORY_ROOT = findRepositoryRoot();
    private static final Path MAIN_CLASSES = REPOSITORY_ROOT.resolve("backend/build/classes/java/main");
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
        assertThat(MAIN_CLASSES)
                .as("mainクラスがコンパイル済みであること")
                .isDirectory();

        return subscriptionsFrom(new ClassFileImporter().importPath(MAIN_CLASSES));
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
