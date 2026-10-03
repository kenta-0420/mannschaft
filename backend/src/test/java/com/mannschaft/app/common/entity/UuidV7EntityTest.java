package com.mannschaft.app.common.entity;

import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UuidV7Entity 単体テスト。
 */
@DisplayName("UuidV7Entity 単体テスト")
class UuidV7EntityTest {

    @Test
    @DisplayName("UuidV7Entity は @MappedSuperclass アノテーションを持つ")
    void isMappedSuperclass() {
        assertThat(UuidV7Entity.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
    }

    @Test
    @DisplayName("UuidV7Entity の id フィールドは UUID 型である")
    void idFieldIsUuidType() throws NoSuchFieldException {
        Field idField = UuidV7Entity.class.getDeclaredField("id");
        assertThat(idField.getType()).isEqualTo(UUID.class);
    }

    @Test
    @DisplayName("UuidV7Entity は抽象クラスである")
    void isAbstractClass() {
        assertThat(Modifier.isAbstract(UuidV7Entity.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("getId() は初期状態で null を返す")
    void getIdReturnsNullInitially() {
        // 匿名サブクラスでインスタンス化して getId() の動作を確認
        UuidV7Entity entity = new UuidV7Entity() {};
        assertThat(entity.getId()).isNull();
    }

    @Test
    @DisplayName("永続化前にnullのIDだけをUUIDv7で採番する")
    void assignsV7OnlyWhenIdIsNull() {
        UuidV7Entity binary = new UuidV7Entity() {};
        binary.assignId();
        assertThat(binary.getId().version()).isEqualTo(7);
        assertThat(binary.getId().variant()).isEqualTo(2);

        UuidV7CharEntity character = new UuidV7CharEntity() {};
        character.assignId();
        assertThat(character.getId().version()).isEqualTo(7);
        assertThat(character.getId().variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("明示されたUUIDv1/v4/v7は自動採番で上書きしない")
    void preservesExplicitIds() {
        for (UUID explicit : new UUID[]{
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
                UUID.fromString("018f0f8d-7b3c-7abc-8def-0123456789ab")}) {
            UuidV7Entity binary = new UuidV7Entity() {};
            binary.setId(explicit);
            binary.assignId();
            assertThat(binary.getId()).isEqualTo(explicit);

            UuidV7CharEntity character = new UuidV7CharEntity() {};
            character.setId(explicit);
            character.assignId();
            assertThat(character.getId()).isEqualTo(explicit);
        }
    }
}
