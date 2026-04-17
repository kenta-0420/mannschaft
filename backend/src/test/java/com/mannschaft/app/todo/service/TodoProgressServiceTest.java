package com.mannschaft.app.todo.service;

import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoProgressService} の単体テスト。
 * 按分アルゴリズム・自動算出・祖先遡及再計算を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TodoProgressService 単体テスト")
class TodoProgressServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private TodoProgressService todoProgressService;

    private static final Long PARENT_ID = 1L;
    private static final Long CHILD1_ID = 10L;
    private static final Long CHILD2_ID = 11L;
    private static final Long CHILD3_ID = 12L;
    private static final Long GRANDCHILD1_ID = 100L;
    private static final Long GRANDCHILD2_ID = 101L;
    private static final Long SCOPE_ID = 999L;

    /** テスト用TODOエンティティを生成する（progressManual=true）。IDはリフレクションで設定。 */
    private TodoEntity buildManualTodo(Long id, Long parentId, BigDecimal progressRate) {
        return buildTodo(id, parentId, progressRate, true);
    }

    /** テスト用TODOエンティティを生成する（progressManual=false: 自動算出モード）。IDはリフレクションで設定。 */
    private TodoEntity buildAutoTodo(Long id, Long parentId, BigDecimal progressRate) {
        return buildTodo(id, parentId, progressRate, false);
    }

    /**
     * IDをリフレクションでセットするTODOビルダーヘルパー。
     * JPA @GeneratedValue のためIDフィールドはfinalではないが、Builderでセットできない場合にリフレクションを使用する。
     */
    private TodoEntity buildTodo(Long id, Long parentId, BigDecimal progressRate, boolean manual) {
        TodoEntity entity = TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("テストTODO " + id)
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(1L)
                .parentId(parentId)
                .depth(parentId == null ? 0 : 1)
                .progressRate(progressRate)
                .progressManual(manual)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        // IDをリフレクションでセット
        try {
            java.lang.reflect.Field f = TodoEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return entity;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Mockito: saveはそのまま引数を返す（LENIENT設定なので使用されなくてもエラーにならない）
        given(todoRepository.save(any(TodoEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("合計按分アルゴリズム（setManualProgressRate）")
    class SetManualProgressRateTest {

        @Test
        @DisplayName("子3件に100%を按分する — [33.34, 33.33, 33.33]（小数点以下2桁切り捨て+端数はchild[0]に加算）")
        void distributeOneHundredToThreeChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, BigDecimal.ZERO);
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, BigDecimal.ZERO);
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, BigDecimal.ZERO);
            TodoEntity child3 = buildManualTodo(CHILD3_ID, PARENT_ID, BigDecimal.ZERO);

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2, child3));
            // 各子の孫はなし
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD1_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD2_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD3_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.setManualProgressRate(parent, new BigDecimal("100.00"));

            // Assert: 保存されたエンティティをキャプチャ
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            List<BigDecimal> rates = captor.getAllValues().stream()
                    .filter(t -> t.getProgressRate() != null)
                    .map(TodoEntity::getProgressRate)
                    .toList();
            // each = 100.00 / 3 = 33.33（小数点以下2桁切り捨て）
            // remainder = 100.00 - 33.33*3 = 0.01
            // child[0] = 33.33 + 0.01 = 33.34, child[1] = 33.33, child[2] = 33.33
            assertThat(rates).anyMatch(r -> r.compareTo(new BigDecimal("33.34")) == 0);
            assertThat(rates).anyMatch(r -> r.compareTo(new BigDecimal("33.33")) == 0);
        }

        @Test
        @DisplayName("子3件に61%を按分する — [20.34, 20.33, 20.33]（小数点以下2桁切り捨て+端数はchild[0]に加算）")
        void distributeSixtyOneToThreeChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, BigDecimal.ZERO);
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, BigDecimal.ZERO);
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, BigDecimal.ZERO);
            TodoEntity child3 = buildManualTodo(CHILD3_ID, PARENT_ID, BigDecimal.ZERO);

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2, child3));
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD1_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD2_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD3_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.setManualProgressRate(parent, new BigDecimal("61.00"));

            // Assert:
            // each = 61.00 / 3 = 20.33（小数点以下2桁切り捨て）
            // remainder = 61.00 - 20.33*3 = 0.01
            // child[0] = 20.33 + 0.01 = 20.34, child[1] = 20.33, child[2] = 20.33
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            List<BigDecimal> rates = captor.getAllValues().stream()
                    .filter(t -> t.getProgressRate() != null)
                    .map(TodoEntity::getProgressRate)
                    .toList();
            assertThat(rates).anyMatch(r -> r.compareTo(new BigDecimal("20.34")) == 0);
            assertThat(rates).anyMatch(r -> r.compareTo(new BigDecimal("20.33")) == 0);
        }

        @Test
        @DisplayName("子3件に0%を按分する — [0.00, 0.00, 0.00]")
        void distributeZeroToThreeChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, BigDecimal.ZERO);
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, new BigDecimal("50.00"));
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, new BigDecimal("30.00"));
            TodoEntity child3 = buildManualTodo(CHILD3_ID, PARENT_ID, new BigDecimal("20.00"));

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2, child3));
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD1_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD2_ID))
                    .willReturn(List.of());
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD3_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.setManualProgressRate(parent, new BigDecimal("0.00"));

            // Assert: 全子が0.00になる
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            long zeroChildren = captor.getAllValues().stream()
                    .filter(t -> t.getProgressManual() != null && t.getProgressManual()
                            && t.getProgressRate() != null
                            && t.getProgressRate().compareTo(BigDecimal.ZERO) == 0)
                    .count();
            // parent + child1 + child2 + child3 のうち childrenは3件
            assertThat(zeroChildren).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("子がいない場合は按分しない")
        void noDistributionWhenNoChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, BigDecimal.ZERO);

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.setManualProgressRate(parent, new BigDecimal("50.00"));

            // Assert: parentのみ保存（子なし）
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            // 保存は1件（parent自身）のみ
            assertThat(captor.getAllValues()).hasSize(1);
            assertThat(captor.getValue().getProgressRate()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("子1件に75%を按分する — [75.00]（端数なし）")
        void distributeSingleChild() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, BigDecimal.ZERO);
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, BigDecimal.ZERO);

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1));
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(CHILD1_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.setManualProgressRate(parent, new BigDecimal("75.00"));

            // Assert: child1 = 75.00（端数なし）
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues()).anyMatch(t ->
                    t.getProgressRate() != null
                    && t.getProgressRate().compareTo(new BigDecimal("75.00")) == 0);
        }
    }

    @Nested
    @DisplayName("自動算出モード切替（switchToAutoMode）")
    class SwitchToAutoModeTest {

        @Test
        @DisplayName("子2件の平均から自動算出し、progressManual=falseになる")
        void switchToAutoModeCalculatesAverageOfChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, new BigDecimal("80.00"));
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, new BigDecimal("60.00"));
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, new BigDecimal("40.00"));

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2));

            // Act
            todoProgressService.switchToAutoMode(parent);

            // Assert: (60 + 40) / 2 = 50.00、progressManual=false
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues()).anyMatch(t ->
                    Boolean.FALSE.equals(t.getProgressManual())
                    && t.getProgressRate() != null
                    && t.getProgressRate().compareTo(new BigDecimal("50.00")) == 0);
        }

        @Test
        @DisplayName("子がいない場合は0.00、progressManual=falseになる")
        void switchToAutoModeWithNoChildren() {
            // Arrange
            TodoEntity parent = buildManualTodo(PARENT_ID, null, new BigDecimal("100.00"));

            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of());

            // Act
            todoProgressService.switchToAutoMode(parent);

            // Assert: 0.00、progressManual=false
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues()).anyMatch(t ->
                    Boolean.FALSE.equals(t.getProgressManual())
                    && t.getProgressRate() != null
                    && t.getProgressRate().compareTo(BigDecimal.ZERO) == 0);
        }
    }

    @Nested
    @DisplayName("祖先遡及再計算（recalculateAncestors）")
    class RecalculateAncestorsTest {

        @Test
        @DisplayName("自動モードの親は子の平均で再計算される")
        void recalculatesAutoModeParent() {
            // Arrange: 親（自動モード）+ 子2件
            TodoEntity parent = buildAutoTodo(PARENT_ID, null, new BigDecimal("30.00"));
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, new BigDecimal("60.00"));
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, new BigDecimal("40.00"));

            given(todoRepository.findByIdAndDeletedAtIsNull(PARENT_ID))
                    .willReturn(Optional.of(parent));
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2));

            // Act: child1 が変化したので親を再計算
            todoProgressService.recalculateAncestors(child1);

            // Assert: 親が (60 + 40) / 2 = 50.00 で更新される
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues()).anyMatch(t ->
                    Boolean.FALSE.equals(t.getProgressManual())
                    && t.getProgressRate() != null
                    && t.getProgressRate().compareTo(new BigDecimal("50.00")) == 0);
        }

        @Test
        @DisplayName("手動モードの親は再計算されない")
        void doesNotRecalculateManualModeParent() {
            // Arrange: 親（手動モード）+ 子1件
            TodoEntity parent = buildManualTodo(PARENT_ID, null, new BigDecimal("80.00"));
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, new BigDecimal("50.00"));

            given(todoRepository.findByIdAndDeletedAtIsNull(PARENT_ID))
                    .willReturn(Optional.of(parent));

            // Act
            todoProgressService.recalculateAncestors(child1);

            // Assert: parentのsaveは呼ばれない（手動モードなので変更なし）
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            // 保存があったとしても親（progressManual=true）は保存されない
            verify(todoRepository, org.mockito.Mockito.never()).save(
                    argThat(t -> Boolean.TRUE.equals(t.getProgressManual())
                               && t.getProgressRate() != null
                               && t.getProgressRate().compareTo(new BigDecimal("80.00")) != 0));
        }

        @Test
        @DisplayName("親がいない場合は何もしない")
        void doesNothingWhenNoParent() {
            // Arrange: 親なしTODO
            TodoEntity orphan = buildManualTodo(PARENT_ID, null, new BigDecimal("50.00"));

            // Act
            todoProgressService.recalculateAncestors(orphan);

            // Assert: saveは呼ばれない
            verify(todoRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("子TODO変更後の再計算（recalculateAfterChildChange）")
    class RecalculateAfterChildChangeTest {

        @Test
        @DisplayName("子追加後に親（自動モード）の進捗率が再計算される")
        void recalculatesAfterChildAdded() {
            // Arrange: 親（自動モード）+ 子3件
            TodoEntity parent = buildAutoTodo(PARENT_ID, null, new BigDecimal("0.00"));
            TodoEntity child1 = buildManualTodo(CHILD1_ID, PARENT_ID, new BigDecimal("90.00"));
            TodoEntity child2 = buildManualTodo(CHILD2_ID, PARENT_ID, new BigDecimal("60.00"));
            TodoEntity child3 = buildManualTodo(CHILD3_ID, PARENT_ID, new BigDecimal("30.00"));

            given(todoRepository.findByIdAndDeletedAtIsNull(PARENT_ID))
                    .willReturn(Optional.of(parent));
            given(todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(PARENT_ID))
                    .willReturn(List.of(child1, child2, child3));

            // Act
            todoProgressService.recalculateAfterChildChange(PARENT_ID);

            // Assert: (90 + 60 + 30) / 3 = 60.00
            ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues()).anyMatch(t ->
                    Boolean.FALSE.equals(t.getProgressManual())
                    && t.getProgressRate() != null
                    && t.getProgressRate().compareTo(new BigDecimal("60.00")) == 0);
        }

        @Test
        @DisplayName("parentIdがnullの場合は何もしない")
        void doesNothingWhenParentIdIsNull() {
            // Act
            todoProgressService.recalculateAfterChildChange(null);

            // Assert: repositoryはアクセスされない
            verify(todoRepository, org.mockito.Mockito.never()).findByIdAndDeletedAtIsNull(anyLong());
            verify(todoRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    /**
     * argThat ヘルパー（Mockitoの引数マッチャー）。
     */
    private static TodoEntity argThat(java.util.function.Predicate<TodoEntity> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}
