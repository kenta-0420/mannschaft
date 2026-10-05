package com.mannschaft.enumguardfixture;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;

import java.util.Set;

/** アプリのentity scanへ混ざらない、enum番人専用のバイトコード検体。 */
public final class PersistedEnumFixtures {
    private PersistedEnumFixtures() {
    }

    /** 台帳未登録の新定数を含む読取り型。 */
    public enum State { OLD, ADDED }

    /** 非永続化の型。 */
    public enum Other { IGNORED }

    /** field アクセスの検体。 */
    @Entity
    public static class FieldEntity {
        @Enumerated(EnumType.STRING)
        State state;
        Other nonPersistent;
    }

    /** STRING 以外を混ぜた検体。 */
    @Entity
    public static class NonStringEntity {
        @Enumerated(EnumType.ORDINAL)
        State ordinal;
        @Enumerated
        State defaultOrdinal;
        Other nonPersistent;
    }

    /** property アクセスの検体。 */
    @Entity
    public static class PropertyEntity {
        @Enumerated(EnumType.STRING)
        State getState() { return State.OLD; }
    }

    /** 継承先がなくても宣言元を走査する検体。 */
    @MappedSuperclass
    public static class MappedBase {
        @Enumerated(EnumType.STRING)
        State state;
    }

    /** 埋め込み先の命名に依存しない検体。 */
    @Embeddable
    public static class EmbeddedValue {
        @Enumerated(EnumType.STRING)
        State state;
    }

    /** ソースを文字列として誤認しないことを確認する検体。 */
    @Entity
    public static class DisguisedEntity {
        // @Enumerated(EnumType.STRING) State fake;
        String fake = "@Enumerated(EnumType.STRING) State state;";
        State nonPersistent;
    }

    /** enumコレクションの検体。 */
    @Entity
    public static class CollectionEntity {
        @jakarta.persistence.ElementCollection
        @Enumerated(EnumType.STRING)
        Set<State> states;
    }

    /** 不明な注釈型での検出漏れを防ぐ検体。 */
    @Entity
    public static class UnsupportedEntity {
        @Enumerated(EnumType.STRING)
        String unsupported;
    }

    /** 非永続enumを新しい属性で永続化する検体。 */
    @Entity
    public static class AdditionalFieldEntity {
        @Enumerated(EnumType.STRING)
        Other additional;
    }

    /** 注釈があってもJPAが保存しない属性の検体。 */
    @Entity
    public static class TransientEntity {
        @Enumerated(EnumType.STRING)
        static State staticState;
        @Enumerated(EnumType.STRING)
        transient State javaTransient;
        @Enumerated(EnumType.STRING)
        @jakarta.persistence.Transient
        State jpaTransient;
    }
}
