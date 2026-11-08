package com.mannschaft.app.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移行用の印の公開契約を実際の注釈情報から検証する。
 */
class TransitionalBridgeTest {

    @Test
    void 型だけに付与できる() {
        Target target = TransitionalBridge.class.getAnnotation(Target.class);

        assertNotNull(target);
        assertArrayEquals(new ElementType[] {ElementType.TYPE}, target.value());
    }

    @Test
    void 実行時まで保持される() {
        Retention retention = TransitionalBridge.class.getAnnotation(Retention.class);

        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void 文書に付与情報を残す() {
        assertTrue(TransitionalBridge.class.isAnnotationPresent(Documented.class));
    }

    @Test
    void 理由は既定値を持たない必須文字列である() throws NoSuchMethodException {
        Method reason = TransitionalBridge.class.getDeclaredMethod("reason");

        assertEquals(String.class, reason.getReturnType());
        assertNull(reason.getDefaultValue());
    }

    @Test
    void 撤去予定レーンは既定値を持たない必須文字列である() throws NoSuchMethodException {
        Method removalLane = TransitionalBridge.class.getDeclaredMethod("removalLane");

        assertEquals(String.class, removalLane.getReturnType());
        assertNull(removalLane.getDefaultValue());
    }

    @Test
    void 橋渡しに記した理由と撤去予定レーンを取得できる() {
        TransitionalBridge bridge = LegacyBridge.class.getDeclaredAnnotation(TransitionalBridge.class);

        assertNotNull(bridge);
        assertEquals("旧FQCNの利用者を維持するため", bridge.reason());
        assertEquals("L5", bridge.removalLane());
    }

    @Test
    void 印を付けていない派生クラスを橋渡しと誤認しない() {
        assertNotNull(LegacyBridge.class.getAnnotation(TransitionalBridge.class));
        assertFalse(TransitionalBridge.class.isAnnotationPresent(Inherited.class));
        assertNull(RegularSubclass.class.getAnnotation(TransitionalBridge.class));
    }

    /** 旧FQCNを維持する橋渡しの検体。 */
    @TransitionalBridge(reason = "旧FQCNの利用者を維持するため", removalLane = "L5")
    private static class LegacyBridge {
    }

    /** 印の継承による対象拡大を検出する検体。 */
    private static class RegularSubclass extends LegacyBridge {
    }
}
