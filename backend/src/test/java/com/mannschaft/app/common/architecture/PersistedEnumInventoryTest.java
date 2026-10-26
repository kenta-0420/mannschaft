package com.mannschaft.app.common.architecture;

import com.mannschaft.enumguardfixture.PersistedEnumFixtures;
import org.junit.jupiter.api.Tag;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.State;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.Other;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.FieldEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.AdditionalFieldEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.TransientEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.NonStringEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.PropertyEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.MappedBase;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.EmbeddedValue;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.DisguisedEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.CollectionEntity;
import com.mannschaft.enumguardfixture.PersistedEnumFixtures.UnsupportedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実バイトコードを使い、定数追加検知と永続化入口の検出範囲を固定する。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class PersistedEnumInventoryTest {
    private static final String PREFIX = PersistedEnumFixtures.class.getName() + "$";

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

}
