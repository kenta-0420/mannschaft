package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReferenceType} の単体テスト。
 *
 * <p>F09.15/16 S0 で追加した {@link ReferenceType#idKind()} のマッピング規約を検証する。
 * 設計書: {@code docs/features/F00_content_visibility_resolver.md} §3.4 (F00-A 案)。
 */
@DisplayName("ReferenceType")
class ReferenceTypeTest {

    @Nested
    @DisplayName("idKind() マッピング規約 (F00-A 案)")
    class IdKindMapping {

        @Test
        @DisplayName("既存 Phase 1 系 (BLOG_POST 等) は BIGINT")
        void phase1_typesAreBigint() {
            assertThat(ReferenceType.BLOG_POST.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
            assertThat(ReferenceType.EVENT.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
            assertThat(ReferenceType.SCHEDULE.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
            assertThat(ReferenceType.CIRCULATION_DOCUMENT.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
            assertThat(ReferenceType.PROPERTY_WORK_PACKAGE.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
        }

        @Test
        @DisplayName("Phase 2 予約 (PERSONAL_TIMETABLE / FOLLOW_LIST) は BIGINT")
        void phase2_reservedAreBigint() {
            assertThat(ReferenceType.PERSONAL_TIMETABLE.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
            assertThat(ReferenceType.FOLLOW_LIST.idKind())
                .isEqualTo(ReferenceType.IdKind.BIGINT);
        }

        @Test
        @DisplayName("F09.15/16 系 (SUCCESSION_* / RESIDENT_*) は UUID_V7")
        void successionAndResidentTypesAreUuidV7() {
            assertThat(ReferenceType.SUCCESSION_PRE_REGISTRATION.idKind())
                .isEqualTo(ReferenceType.IdKind.UUID_V7);
            assertThat(ReferenceType.SUCCESSION_COVENANTS.idKind())
                .isEqualTo(ReferenceType.IdKind.UUID_V7);
            assertThat(ReferenceType.RESIDENT_ACTIVITY_SNAPSHOT.idKind())
                .isEqualTo(ReferenceType.IdKind.UUID_V7);
        }

        @Test
        @DisplayName("全 enum 値が idKind() を返す (NPE / NoSuchElementException が発生しない)")
        void allValuesReturnIdKind() {
            for (ReferenceType type : ReferenceType.values()) {
                assertThat(type.idKind())
                    .as("idKind() of %s must not be null", type)
                    .isNotNull();
            }
        }
    }
}
