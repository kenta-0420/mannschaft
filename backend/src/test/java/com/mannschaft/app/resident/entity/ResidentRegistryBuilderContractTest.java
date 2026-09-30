package com.mannschaft.app.resident.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 居住者の新規登録 builder が状態変更の抜け道にならないことを検証する。 */
class ResidentRegistryBuilderContractTest {

    private static final Set<String> REGISTRATION_INPUTS = Set.of(
            "dwellingUnitId", "userId", "residentType", "lastName", "firstName",
            "lastNameKana", "firstNameKana", "phone", "email", "emergencyContact",
            "lastNameHash", "firstNameHash", "moveInDate", "ownershipRatio", "isPrimary", "notes");

    @Test
    @DisplayName("登録 builder の公開入力は安全な16項目だけである")
    void 登録入力以外の状態をbuilderで設定できない() {
        Class<?> builderType = ResidentRegistryEntity.builder().getClass();
        Set<String> inputs = Arrays.stream(builderType.getMethods())
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType().isAssignableFrom(builderType))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(Arrays.stream(builderType.getMethods())
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType().isAssignableFrom(builderType))).hasSize(16);
        assertThat(inputs).containsExactlyInAnyOrderElementsOf(REGISTRATION_INPUTS);
    }

    @Test
    @DisplayName("公開 toBuilder と公開 constructor から既存状態を複製・再設定できない")
    void 状態をコピーする公開生成経路がない() {
        assertThat(Arrays.stream(ResidentRegistryEntity.class.getMethods())
                .map(Method::getName)).doesNotContain("toBuilder");
        assertThat(ResidentRegistryEntity.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(ResidentRegistryEntity.class.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 0)
                .map(constructor -> Modifier.isProtected(constructor.getModifiers())))
                .containsExactly(true);
    }

    @Test
    @DisplayName("登録入力を保持し、機微状態と監査情報は初期状態から始まる")
    void 登録入力と初期状態を保持する() {
        LocalDate moveInDate = LocalDate.of(2026, 1, 2);
        BigDecimal ownershipRatio = new BigDecimal("0.5000");
        ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                .dwellingUnitId(10L).userId(20L).residentType("OWNER")
                .lastName("山田").firstName("花子")
                .lastNameKana("ヤマダ").firstNameKana("ハナコ")
                .phone("09012345678").email("resident@example.com").emergencyContact("家族")
                .lastNameHash("last-hash").firstNameHash("first-hash")
                .moveInDate(moveInDate).ownershipRatio(ownershipRatio).isPrimary(true).notes("備考")
                .build();

        assertThat(entity).extracting(
                "dwellingUnitId", "userId", "residentType", "lastName", "firstName",
                "lastNameKana", "firstNameKana", "phone", "email", "emergencyContact",
                "lastNameHash", "firstNameHash", "moveInDate", "ownershipRatio", "isPrimary", "notes")
                .containsExactly(10L, 20L, "OWNER", "山田", "花子", "ヤマダ", "ハナコ",
                        "09012345678", "resident@example.com", "家族", "last-hash", "first-hash",
                        moveInDate, ownershipRatio, true, "備考");
        assertThat(entity.getEncryptionKeyVersion()).isEqualTo(1);
        assertThat(entity.getDeathStatus()).isEqualTo(DeathStatus.ALIVE);
        assertThat(entity.getOccupancyStatus()).isEqualTo(OccupancyStatus.UNKNOWN);
        assertThat(entity.getIsVerified()).isFalse();
        assertThat(entity.getIsSecondaryHome()).isFalse();
        assertThat(entity).extracting("id", "createdAt", "updatedAt", "moveOutDate", "deletedAt",
                "verifiedBy", "verifiedAt", "deathStatusChangedAt", "deathStatusChangedBy",
                "presumedDeathScore", "activityLastSeenAt", "lastAnnualReviewAt", "annualReviewDueAt",
                "ageEstimated", "moveOutChangedBy", "moveOutChangedAt").containsOnlyNulls();
    }

    @Test
    @DisplayName("未指定の任意項目はnull、主居住者フラグはfalseである")
    void 未指定項目の既定値を維持する() {
        ResidentRegistryEntity entity = ResidentRegistryEntity.builder().build();

        assertThat(entity.getIsPrimary()).isFalse();
        assertThat(entity).extracting("userId", "lastNameKana", "firstNameKana", "phone", "email",
                "emergencyContact", "ownershipRatio", "notes").containsOnlyNulls();
    }
}
