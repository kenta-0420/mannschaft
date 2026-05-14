package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.OrgWideSafetyCheckDto;
import com.mannschaft.app.residencestatus.entity.OrgWideSafetyCheck;
import com.mannschaft.app.residencestatus.repository.OrgWideSafetyCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrgWideSafetyCheckService} のユニットテスト（F09.16 S3-C）。
 *
 * <p>外部依存（Repository / AccessControlService）はすべて Mockito スタブ化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrgWideSafetyCheckService")
class OrgWideSafetyCheckServiceTest {

    @Mock
    private OrgWideSafetyCheckRepository safetyCheckRepo;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private OrgWideSafetyCheckService service;

    static final Long ORG_ID = 100L;
    static final Long ADMIN_USER = 1001L;
    static final Long MEMBER_USER = 1002L;

    // ─── ヘルパー ──────────────────────────────────────────────────────

    private OrgWideSafetyCheck buildCheck(UUID id, Long organizationId, Long triggeredBy,
                                           String reason, LocalDateTime closedAt) {
        OrgWideSafetyCheck c = OrgWideSafetyCheck.builder()
                .organizationId(organizationId)
                .safetyCheckId(0L)   // v1 仮 ID
                .triggeredBy(triggeredBy)
                .triggeredAt(LocalDateTime.now())
                .triggerReason(reason)
                .build();
        setField(c, "id", id);
        setField(c, "createdAt", LocalDateTime.now());
        if (closedAt != null) {
            setField(c, "closedAt", closedAt);
        }
        return c;
    }

    /** リフレクションで private フィールドに値を設定するヘルパー */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new RuntimeException("フィールド " + fieldName + " が見つかりません");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── triggerOrgWideSafetyCheck ──────────────────────────────────

    @Nested
    @DisplayName("triggerOrgWideSafetyCheck")
    class TriggerOrgWideSafetyCheck {

        @Test
        @DisplayName("ADMIN が横展開安否確認を正常に発動できる")
        void adminCanTrigger() {
            // given
            UUID checkId = UUID.randomUUID();
            OrgWideSafetyCheck saved = buildCheck(checkId, ORG_ID, ADMIN_USER, "地震発生のため", null);
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(safetyCheckRepo.save(any())).thenReturn(saved);

            // when
            OrgWideSafetyCheckDto dto = service.triggerOrgWideSafetyCheck(ORG_ID, ADMIN_USER, "地震発生のため");

            // then
            assertThat(dto.getId()).isEqualTo(checkId);
            assertThat(dto.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(dto.getTriggeredBy()).isEqualTo(ADMIN_USER);
            assertThat(dto.getTriggerReason()).isEqualTo("地震発生のため");
            assertThat(dto.getClosedAt()).isNull();
            // v1 では safetyCheckId=0L を DTO では null に変換する
            assertThat(dto.getSafetyCheckId()).isNull();
            verify(safetyCheckRepo).save(any(OrgWideSafetyCheck.class));
        }

        @Test
        @DisplayName("非 ADMIN は DASHBOARD_ACCESS_FORBIDDEN")
        void nonAdminForbidden() {
            // given
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.triggerOrgWideSafetyCheck(ORG_ID, MEMBER_USER, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("保存されたレコードの内容が正しい")
        void savedRecordContainsCorrectData() {
            // given
            String reason = "台風接近のため";
            OrgWideSafetyCheck saved = buildCheck(UUID.randomUUID(), ORG_ID, ADMIN_USER, reason, null);
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);

            ArgumentCaptor<OrgWideSafetyCheck> captor = ArgumentCaptor.forClass(OrgWideSafetyCheck.class);
            when(safetyCheckRepo.save(captor.capture())).thenReturn(saved);

            // when
            service.triggerOrgWideSafetyCheck(ORG_ID, ADMIN_USER, reason);

            // then: 保存されたエンティティの内容を確認
            OrgWideSafetyCheck captured = captor.getValue();
            assertThat(captured.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(captured.getTriggeredBy()).isEqualTo(ADMIN_USER);
            assertThat(captured.getTriggerReason()).isEqualTo(reason);
            assertThat(captured.getSafetyCheckId()).isEqualTo(0L); // 仮 ID
            assertThat(captured.getTriggeredAt()).isNotNull();
            assertThat(captured.getClosedAt()).isNull();
        }
    }

    // ─── getActiveChecks ───────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveChecks")
    class GetActiveChecks {

        @Test
        @DisplayName("ADMIN は未クローズ一覧を取得できる")
        void adminCanGetActiveChecks() {
            // given
            OrgWideSafetyCheck c1 = buildCheck(UUID.randomUUID(), ORG_ID, ADMIN_USER, "地震", null);
            OrgWideSafetyCheck c2 = buildCheck(UUID.randomUUID(), ORG_ID, ADMIN_USER, "火災", null);
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(safetyCheckRepo.findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of(c1, c2));

            // when
            List<OrgWideSafetyCheckDto> list = service.getActiveChecks(ORG_ID, ADMIN_USER);

            // then
            assertThat(list).hasSize(2);
            assertThat(list).allMatch(dto -> dto.getClosedAt() == null);
        }

        @Test
        @DisplayName("非 ADMIN は DASHBOARD_ACCESS_FORBIDDEN")
        void nonAdminForbidden() {
            // given
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.getActiveChecks(ORG_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("クローズ済み件数は含まれない（リポジトリが未クローズのみ返す）")
        void closedAreExcluded() {
            // given: リポジトリは closedAt IS NULL のものだけ返す前提
            OrgWideSafetyCheck active = buildCheck(UUID.randomUUID(), ORG_ID, ADMIN_USER, "地震", null);
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(safetyCheckRepo.findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of(active));

            // when
            List<OrgWideSafetyCheckDto> list = service.getActiveChecks(ORG_ID, ADMIN_USER);

            // then
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getClosedAt()).isNull();
        }

        @Test
        @DisplayName("未クローズ一覧が空の場合は空リストを返す")
        void emptyList() {
            // given
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(safetyCheckRepo.findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of());

            // when
            List<OrgWideSafetyCheckDto> list = service.getActiveChecks(ORG_ID, ADMIN_USER);

            // then
            assertThat(list).isEmpty();
        }
    }
}
