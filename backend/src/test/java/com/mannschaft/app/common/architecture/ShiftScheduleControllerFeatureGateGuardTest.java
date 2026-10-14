package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shift.controller.ShiftScheduleController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2151: {@link ShiftScheduleController} の全マッピングに機能フラグ
 * {@code FEATURE_SHIFT_ENABLED} が付いていることを固定する番人。
 *
 * <p>以前は9本中 {@code listSchedules} の1本にしか {@link RequireFeature} が無く、フラグを閉じても
 * 作成・更新・削除・ステータス遷移が素通りしていた。マッピングを足したのにフラグを付け忘れると、
 * 目視では見つからないまま「一覧だけ見えないが作成も削除もできる」状態になるため、
 * マッピング数と付与数の一致をリフレクションで検査する。</p>
 */
@DisplayName("ShiftScheduleController の機能フラグ付与番人")
class ShiftScheduleControllerFeatureGateGuardTest {

    private static final String FLAG = "FEATURE_SHIFT_ENABLED";

    @Test
    @DisplayName("全マッピングメソッドが @RequireFeature(FEATURE_SHIFT_ENABLED) を持つ")
    void everyMappedMethodDeclaresShiftFlag() {
        List<Method> mapped = Arrays.stream(ShiftScheduleController.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();

        assertThat(mapped).as("マッピングメソッドを1本も検出できない（走査の退行）").isNotEmpty();

        List<String> ungated = mapped.stream()
                .filter(m -> !isGated(m))
                .map(Method::getName)
                .sorted()
                .toList();
        assertThat(ungated)
                .as("@RequireFeature(\"%s\") が無いエンドポイント。付け忘れるとフラグを閉じても素通りする", FLAG)
                .isEmpty();

        long gatedCount = mapped.stream().filter(ShiftScheduleControllerFeatureGateGuardTest::isGated).count();
        assertThat(gatedCount).as("マッピング数と付与数の一致").isEqualTo(mapped.size());
    }

    private static boolean isGated(Method method) {
        RequireFeature onMethod = method.getAnnotation(RequireFeature.class);
        RequireFeature onClass = method.getDeclaringClass().getAnnotation(RequireFeature.class);
        RequireFeature effective = onMethod != null ? onMethod : onClass;
        return effective != null && Arrays.asList(effective.value()).contains(FLAG);
    }
}
