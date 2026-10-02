package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実バイトコードを使い、定数追加検知と永続化入口の検出範囲を固定する。 */
class PersistedEnumInventoryTest {
    private static final String PREFIX = PersistedEnumInventoryTest.class.getName() + "$";

    @Test
    void STRINGフィールドの全定数を列挙する() {
        assertThat(scan(FieldEntity.class)).containsExactlyInAnyOrder(
                PREFIX + "State#OLD", PREFIX + "State#ADDED");
    }

    @Test
    void 基準にない追加定数は差分として残る() {
        Set<String> actual = new java.util.TreeSet<>(scan(FieldEntity.class));
        actual.removeAll(Set.of(PREFIX + "State#OLD"));
        assertThat(actual).containsExactly(PREFIX + "State#ADDED");
    }

    @Test
    void 新しいfieldで既存の非永続enumを保存すると追加を検知する() {
        Set<String> actual = new java.util.TreeSet<>(scan(FieldEntity.class, AdditionalFieldEntity.class));
        actual.removeAll(scan(FieldEntity.class));
        assertThat(actual).containsExactly(PREFIX + "Other#IGNORED");
    }

    @Test
    void staticとTransientのSTRING注釈は永続化入口にしない() {
        assertThat(scan(TransientEntity.class)).isEmpty();
    }

    @Test
    void 非永続enumとORDINALと既定注釈は対象外() {
        assertThat(scan(NonStringEntity.class)).isEmpty();
    }

    @Test
    void getterによるpropertyアクセスも列挙する() {
        assertThat(scan(PropertyEntity.class)).containsExactlyInAnyOrder(
                PREFIX + "State#OLD", PREFIX + "State#ADDED");
    }

    @Test
    void MappedSuperclassとEmbeddableの入口も列挙する() {
        assertThat(scan(MappedBase.class, EmbeddedValue.class)).containsExactlyInAnyOrder(
                PREFIX + "State#OLD", PREFIX + "State#ADDED");
    }

    @Test
    void 注釈を装うコメントと文字列は永続化を増やさない() {
        assertThat(scan(DisguisedEntity.class)).isEmpty();
    }

    @Test
    void enumコレクションの定数も列挙する() {
        assertThat(scan(CollectionEntity.class)).containsExactlyInAnyOrder(
                PREFIX + "State#OLD", PREFIX + "State#ADDED");
    }

    @Test
    void STRING注釈の未対応型は無言で除外せず失敗する() {
        assertThatThrownBy(() -> scan(UnsupportedEntity.class))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unsupported");
    }

    private static Set<String> scan(Class<?>... types) {
        Class<?>[] candidates = new Class<?>[types.length + 2];
        System.arraycopy(types, 0, candidates, 0, types.length);
        candidates[types.length] = State.class;
        candidates[types.length + 1] = Other.class;
        JavaClasses classes = new ClassFileImporter().importClasses(candidates);
        return PersistedEnumInventory.constants(classes);
    }

    /** 台帳未登録の新定数を含む読取り型。 */
    enum State { OLD, ADDED }

    /** 非永続化の型。 */
    enum Other { IGNORED }

    /** field アクセスの検体。 */
    @Entity
    static class FieldEntity {
        @Enumerated(EnumType.STRING)
        State state;
        Other nonPersistent;
    }

    /** STRING 以外を混ぜた検体。 */
    @Entity
    static class NonStringEntity {
        @Enumerated(EnumType.ORDINAL)
        State ordinal;
        @Enumerated
        State defaultOrdinal;
        Other nonPersistent;
    }

    /** property アクセスの検体。 */
    @Entity
    static class PropertyEntity {
        @Enumerated(EnumType.STRING)
        State getState() { return State.OLD; }
    }

    /** 継承先がなくても宣言元を走査する検体。 */
    @MappedSuperclass
    static class MappedBase {
        @Enumerated(EnumType.STRING)
        State state;
    }

    /** 埋め込み先の命名に依存しない検体。 */
    @Embeddable
    static class EmbeddedValue {
        @Enumerated(EnumType.STRING)
        State state;
    }

    /** ソースを文字列として誤認しないことを確認する検体。 */
    @Entity
    static class DisguisedEntity {
        // @Enumerated(EnumType.STRING) State fake;
        String fake = "@Enumerated(EnumType.STRING) State state;";
        State nonPersistent;
    }

    /** enumコレクションの検体。 */
    @Entity
    static class CollectionEntity {
        @jakarta.persistence.ElementCollection
        @Enumerated(EnumType.STRING)
        Set<State> states;
    }

    /** 不明な注釈型での検出漏れを防ぐ検体。 */
    @Entity
    static class UnsupportedEntity {
        @Enumerated(EnumType.STRING)
        String unsupported;
    }

    /** 非永続enumを新しい属性で永続化する検体。 */
    @Entity
    static class AdditionalFieldEntity {
        @Enumerated(EnumType.STRING)
        Other additional;
    }

    /** 注釈があってもJPAが保存しない属性の検体。 */
    @Entity
    static class TransientEntity {
        @Enumerated(EnumType.STRING)
        static State staticState;
        @Enumerated(EnumType.STRING)
        transient State javaTransient;
        @Enumerated(EnumType.STRING)
        @jakarta.persistence.Transient
        State jpaTransient;
    }
}
