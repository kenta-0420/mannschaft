package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.circulation.visibility.CirculationCommentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F03.16 <b>AC-37</b>（Resolver の一意性・設計書 §9.4 / §4.5.1 チェックリスト #8）。
 *
 * <p>{@link ContentVisibilityChecker} は {@code referenceType()} をキーに Resolver の
 * ディスパッチ表を構築する。同じ値を 2 つの Resolver が返すと起動が
 * {@link IllegalStateException} で落ちる（＝本番で初めて分かる）。
 * 本テストはそれを<b>ビルド時</b>に前倒しし、とりわけ
 * {@link ReferenceType#SCHEDULE_COMMENT}（予定コメント）と {@link ReferenceType#COMMENT}
 * （回覧板コメント）が<b>別々に解決される</b>ことを名指しで固定する。</p>
 *
 * <h2>なぜバイトコードから読むのか</h2>
 * <p>Spring コンテキストを立ち上げれば重複は検出できるが、それは統合テストの重さを
 * 単体 AC に持ち込むことになる。各 {@code referenceType()} の実装は
 * {@code return ReferenceType.XXX;} という enum 定数の読み出し 1 個で構成されるため、
 * ArchUnit の {@link JavaMethod#getFieldAccesses()} で参照先の定数名を静的に読める。
 * これによりクラスパス上の<b>全</b> Resolver を、インスタンス化せずに突合できる。</p>
 */
@DisplayName("F03.16 AC-37 ContentVisibilityResolver の referenceType 一意性")
class ScheduleCommentResolverUniquenessTest {

    private static final String RESOLVER_IF = ContentVisibilityResolver.class.getName();
    private static final String REFERENCE_TYPE_FQN = ReferenceType.class.getName();

    /** クラスパス上の全 Resolver 実装から「クラス名 → 宣言している referenceType 定数名」を静的に読む。 */
    private static Map<String, String> declaredReferenceTypes() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");

        Map<String, String> byClass = new LinkedHashMap<>();
        for (JavaClass clazz : imported) {
            if (clazz.isInterface() || clazz.getModifiers().toString().contains("ABSTRACT")) {
                continue;
            }
            boolean isResolver = clazz.getAllRawInterfaces().stream()
                    .anyMatch(i -> RESOLVER_IF.equals(i.getName()));
            if (!isResolver) {
                continue;
            }
            Optional<JavaMethod> method = clazz.getMethods().stream()
                    .filter(m -> "referenceType".equals(m.getName()) && m.getRawParameterTypes().isEmpty())
                    .findFirst();
            if (method.isEmpty()) {
                // 基底クラスで実装されている等。静的に読めないものは突合対象外。
                continue;
            }
            List<String> constants = method.get().getFieldAccesses().stream()
                    .filter(a -> REFERENCE_TYPE_FQN.equals(a.getTargetOwner().getName()))
                    .map(a -> a.getTarget().getName())
                    .filter(name -> !"$VALUES".equals(name))
                    .toList();
            if (constants.size() == 1) {
                byClass.put(clazz.getName(), constants.get(0));
            }
        }
        return byClass;
    }

    @Test
    @DisplayName("読み取り機構そのものの自己検証 — 既知の 2 クラスを実際に読めている")
    void 読み取り機構が実際に定数を読めている() {
        // この自己検証が無いと、読み取りが 0 件しか拾えていない（＝何も検査していない）状態でも
        // 下の一意性テストが「重複なし」で緑になってしまう。
        Map<String, String> declared = declaredReferenceTypes();

        assertThat(declared)
                .as("Resolver 実装をバイトコードから 1 つも拾えていない場合、一意性検査は偽緑になる")
                .hasSizeGreaterThan(5);
        assertThat(declared)
                .containsEntry(ScheduleCommentVisibilityResolver.class.getName(), "SCHEDULE_COMMENT")
                .containsEntry(CirculationCommentVisibilityResolver.class.getName(), "COMMENT");
    }

    @Test
    @DisplayName("AC-37 referenceType が重複する Resolver が 1 つも存在しない")
    void referenceTypeは重複しない() {
        Map<String, String> declared = declaredReferenceTypes();

        Map<String, List<String>> byType = new LinkedHashMap<>();
        declared.forEach((clazz, type) ->
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(clazz));

        List<String> duplicates = byType.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " -> " + e.getValue())
                .toList();

        assertThat(duplicates)
                .as("同じ referenceType を返す Resolver が複数あると ContentVisibilityChecker の "
                        + "ディスパッチ表構築が IllegalStateException で落ち、起動できない")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-37 SCHEDULE_COMMENT と COMMENT は別々の Resolver へ解決される")
    void 予定コメントと回覧板コメントは別解決される() {
        ContentVisibilityResolver<?> scheduleComment = stubResolver(ReferenceType.SCHEDULE_COMMENT);
        ContentVisibilityResolver<?> circulationComment = stubResolver(ReferenceType.COMMENT);

        ContentVisibilityChecker checker = new ContentVisibilityChecker(
                List.of(scheduleComment, circulationComment), Mockito.mock(VisibilityMetrics.class));

        // 別々に登録できる＝衝突していない。衝突していれば構築時に例外で落ちる。
        assertThat(checker).isNotNull();
        assertThat(ReferenceType.SCHEDULE_COMMENT).isNotEqualTo(ReferenceType.COMMENT);
    }

    @Test
    @DisplayName("検出器の自己検証 — 重複を渡せば実際に起動失敗する（黙って通らない）")
    void 重複を渡せば起動に失敗する() {
        assertThatThrownBy(() -> new ContentVisibilityChecker(
                List.of(stubResolver(ReferenceType.SCHEDULE_COMMENT),
                        stubResolver(ReferenceType.SCHEDULE_COMMENT)),
                Mockito.mock(VisibilityMetrics.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("SCHEDULE_COMMENT");
    }

    private static ContentVisibilityResolver<?> stubResolver(ReferenceType type) {
        return new ContentVisibilityResolver<Enum<?>>() {
            @Override
            public ReferenceType referenceType() {
                return type;
            }

            @Override
            public boolean canView(Long contentId, Long viewerUserId) {
                return false;
            }

            @Override
            public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
                return Set.of();
            }
        };
    }

    /** 未使用の import 警告を避けるためのダミー参照（{@link JavaFieldAccess} は上記で型推論に使用）。 */
    @SuppressWarnings("unused")
    private static Class<?> unusedTypeAnchor() {
        return JavaFieldAccess.class;
    }
}
