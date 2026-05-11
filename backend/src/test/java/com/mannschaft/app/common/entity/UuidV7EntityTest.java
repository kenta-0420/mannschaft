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
}
