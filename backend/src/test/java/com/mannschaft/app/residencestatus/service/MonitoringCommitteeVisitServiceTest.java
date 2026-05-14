package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.MonitoringCommitteeVisitDto;
import com.mannschaft.app.residencestatus.entity.MonitoringCommitteeVisit;
import com.mannschaft.app.residencestatus.repository.MonitoringCommitteeVisitRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MonitoringCommitteeVisitService} のユニットテスト（F09.16 S3-C）。
 *
 * <p>外部依存（Repository / AccessControlService）はすべて Mockito スタブ化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoringCommitteeVisitService")
class MonitoringCommitteeVisitServiceTest {

    @Mock
    private MonitoringCommitteeVisitRepository visitRepo;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MonitoringCommitteeVisitService service;

    static final Long ORG_ID = 100L;
    static final Long ADMIN_USER = 1001L;
    static final Long MEMBER_USER = 1002L;
    static final Long COMMITTEE_ID = 10L;
    static final Long REGISTRY_ID = 200L;
    static final Long DWELLING_ID = 300L;
    static final Long SUBJECT_USER = 9001L;
    static final Long VISITOR_USER = 9002L;

    // ─── ヘルパー ──────────────────────────────────────────────────────

    /**
     * テスト用 MonitoringCommitteeVisit を生成する。
     */
    private MonitoringCommitteeVisit buildVisit(UUID id, LocalDateTime createdAt) {
        MonitoringCommitteeVisit v = MonitoringCommitteeVisit.builder()
                .organizationId(ORG_ID)
                .committeeId(COMMITTEE_ID)
                .residentRegistryId(REGISTRY_ID)
                .dwellingUnitId(DWELLING_ID)
                .subjectUserId(SUBJECT_USER)
                .visitorUserId(VISITOR_USER)
                .visitedAt(LocalDateTime.now().minusHours(1))
                .contactResult("MET")
                .considerationMemoEncrypted("メモ")
                .nextVisitRecommendedAt(LocalDate.now().plusDays(30))
                .consentCovenantId(null)
                .build();
        setField(v, "id", id);
        setField(v, "createdAt", createdAt);
        return v;
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

    // ─── createVisit ────────────────────────────────────────────────────

    @Nested
    @DisplayName("createVisit")
    class CreateVisit {

        @Test
        @DisplayName("正常に訪問記録を作成できる")
        void success() {
            // given: checkAdminOrAbove は void メソッドのため mock はデフォルトで何もしない（ADMIN 確認通過）
            UUID visitId = UUID.randomUUID();
            MonitoringCommitteeVisit saved = buildVisit(visitId, LocalDateTime.now());
            when(visitRepo.save(any())).thenReturn(saved);

            // when
            MonitoringCommitteeVisitDto dto = service.createVisit(
                    ORG_ID, COMMITTEE_ID, REGISTRY_ID, DWELLING_ID,
                    SUBJECT_USER, VISITOR_USER, LocalDateTime.now(),
                    "MET", "メモ", LocalDate.now().plusDays(30), null, ADMIN_USER);

            // then
            assertThat(dto.getId()).isEqualTo(visitId);
            assertThat(dto.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(dto.getCommitteeId()).isEqualTo(COMMITTEE_ID);
            assertThat(dto.getContactResult()).isEqualTo("MET");
            verify(visitRepo).save(any(MonitoringCommitteeVisit.class));
        }

        @Test
        @DisplayName("ADMIN でない場合は SNAPSHOT_ACCESS_FORBIDDEN")
        void nonAdminForbidden() {
            // given: checkAdminOrAbove (void) を doThrow でスタブして非ADMIN を模擬
            org.mockito.Mockito.doThrow(new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN))
                    .when(accessControlService).checkAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.createVisit(
                    ORG_ID, COMMITTEE_ID, REGISTRY_ID, DWELLING_ID,
                    SUBJECT_USER, VISITOR_USER, LocalDateTime.now(),
                    "MET", null, null, null, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("consentCovenantId が指定された場合も正常に保存される")
        void withConsentCovenantId() {
            // given
            UUID consentId = UUID.randomUUID();
            UUID visitId = UUID.randomUUID();
            MonitoringCommitteeVisit saved = buildVisit(visitId, LocalDateTime.now());
            setField(saved, "consentCovenantId", consentId);
            when(visitRepo.save(any())).thenReturn(saved);

            // when
            MonitoringCommitteeVisitDto dto = service.createVisit(
                    ORG_ID, COMMITTEE_ID, REGISTRY_ID, DWELLING_ID,
                    SUBJECT_USER, VISITOR_USER, LocalDateTime.now(),
                    "MET", null, null, consentId, ADMIN_USER);

            // then
            assertThat(dto.getConsentCovenantId()).isEqualTo(consentId);
        }
    }

    // ─── updateVisit ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateVisit")
    class UpdateVisit {

        @Test
        @DisplayName("存在しない visitId は MONITORING_VISIT_NOT_FOUND")
        void notFound() {
            // given
            UUID visitId = UUID.randomUUID();
            when(visitRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, ORG_ID))
                    .thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> service.updateVisit(
                    ORG_ID, visitId, ADMIN_USER, "MET", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.MONITORING_VISIT_NOT_FOUND);
        }

        @Test
        @DisplayName("作成から 24h 超過は MONITORING_VISIT_UPDATE_EXPIRED")
        void updateExpired() {
            // given
            UUID visitId = UUID.randomUUID();
            MonitoringCommitteeVisit visit = buildVisit(visitId, LocalDateTime.now().minusHours(25));
            when(visitRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, ORG_ID))
                    .thenReturn(Optional.of(visit));

            // when/then
            assertThatThrownBy(() -> service.updateVisit(
                    ORG_ID, visitId, VISITOR_USER, "NO_RESPONSE", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.MONITORING_VISIT_UPDATE_EXPIRED);
        }

        @Test
        @DisplayName("24h 以内かつ訪問者本人なら更新成功")
        void updateByVisitorSuccess() {
            // given
            UUID visitId = UUID.randomUUID();
            MonitoringCommitteeVisit visit = buildVisit(visitId, LocalDateTime.now().minusHours(1));
            when(visitRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, ORG_ID))
                    .thenReturn(Optional.of(visit));
            when(accessControlService.isAdminOrAbove(VISITOR_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);
            when(visitRepo.save(any())).thenReturn(visit);

            // when
            MonitoringCommitteeVisitDto dto = service.updateVisit(
                    ORG_ID, visitId, VISITOR_USER, "NO_RESPONSE", "更新メモ", null);

            // then
            assertThat(dto).isNotNull();
            verify(visitRepo).save(visit);
        }

        @Test
        @DisplayName("24h 以内かつ ADMIN なら更新成功")
        void updateByAdminSuccess() {
            // given
            UUID visitId = UUID.randomUUID();
            MonitoringCommitteeVisit visit = buildVisit(visitId, LocalDateTime.now().minusHours(1));
            when(visitRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, ORG_ID))
                    .thenReturn(Optional.of(visit));
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.save(any())).thenReturn(visit);

            // when
            MonitoringCommitteeVisitDto dto = service.updateVisit(
                    ORG_ID, visitId, ADMIN_USER, "MET", null, LocalDate.now().plusDays(14));

            // then
            assertThat(dto).isNotNull();
            verify(visitRepo).save(visit);
        }

        @Test
        @DisplayName("24h 以内だが訪問者でも ADMIN でもない場合は SNAPSHOT_ACCESS_FORBIDDEN")
        void updateByOtherForbidden() {
            // given
            UUID visitId = UUID.randomUUID();
            Long otherUser = 9999L;
            MonitoringCommitteeVisit visit = buildVisit(visitId, LocalDateTime.now().minusHours(1));
            when(visitRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, ORG_ID))
                    .thenReturn(Optional.of(visit));
            when(accessControlService.isAdminOrAbove(otherUser, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.updateVisit(
                    ORG_ID, visitId, otherUser, "MET", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }
    }

    // ─── getVisitsByCommittee ──────────────────────────────────────────

    @Nested
    @DisplayName("getVisitsByCommittee")
    class GetVisitsByCommittee {

        @Test
        @DisplayName("ADMIN は委員会の訪問記録一覧を取得できる")
        void adminCanGetByCommittee() {
            // given
            MonitoringCommitteeVisit v1 = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            MonitoringCommitteeVisit v2 = buildVisit(UUID.randomUUID(), LocalDateTime.now().minusDays(1));
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.findByCommitteeIdAndDeletedAtIsNullOrderByVisitedAtDesc(COMMITTEE_ID))
                    .thenReturn(List.of(v1, v2));

            // when
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByCommittee(ORG_ID, COMMITTEE_ID, ADMIN_USER);

            // then
            assertThat(list).hasSize(2);
        }

        @Test
        @DisplayName("非 ADMIN は SNAPSHOT_ACCESS_FORBIDDEN")
        void nonAdminForbidden() {
            // given
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.getVisitsByCommittee(ORG_ID, COMMITTEE_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }
    }

    // ─── getVisitsByWatcher ──────────────────────────────────────────

    @Nested
    @DisplayName("getVisitsByWatcher (S4-A)")
    class GetVisitsByWatcher {

        @Test
        @DisplayName("ADMIN はどの WATCHER の訪問履歴も取得できる")
        void adminCanGetByWatcher() {
            // given
            MonitoringCommitteeVisit v1 = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            MonitoringCommitteeVisit v2 = buildVisit(UUID.randomUUID(), LocalDateTime.now().minusDays(3));
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.findByVisitorUserIdAndOrganizationIdAndDeletedAtIsNullOrderByVisitedAtDesc(
                    VISITOR_USER, ORG_ID))
                    .thenReturn(List.of(v1, v2));

            // when
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByWatcher(ORG_ID, VISITOR_USER, ADMIN_USER);

            // then
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getVisitorUserId()).isEqualTo(VISITOR_USER);
        }

        @Test
        @DisplayName("本人（isSelf）は自分の訪問履歴を取得できる")
        void selfCanGetOwnVisits() {
            // given: VISITOR_USER が自分自身の訪問履歴を取得する
            MonitoringCommitteeVisit v = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            when(accessControlService.isAdminOrAbove(VISITOR_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);
            when(visitRepo.findByVisitorUserIdAndOrganizationIdAndDeletedAtIsNullOrderByVisitedAtDesc(
                    VISITOR_USER, ORG_ID))
                    .thenReturn(List.of(v));

            // when: requestUserId == visitorUserId (本人)
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByWatcher(ORG_ID, VISITOR_USER, VISITOR_USER);

            // then
            assertThat(list).hasSize(1);
        }

        @Test
        @DisplayName("第三者（ADMIN でも本人でもない）は SNAPSHOT_ACCESS_FORBIDDEN")
        void thirdPartyForbidden() {
            // given: MEMBER_USER は ADMIN でも VISITOR_USER 本人でもない
            Long thirdPartyUser = 9999L;
            when(accessControlService.isAdminOrAbove(thirdPartyUser, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.getVisitsByWatcher(ORG_ID, VISITOR_USER, thirdPartyUser))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("ADMIN かつ本人の場合も成功する")
        void adminAndSelfBothSucceed() {
            // given: VISITOR_USER が ADMIN でもあり、かつ本人の場合
            MonitoringCommitteeVisit v = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            when(accessControlService.isAdminOrAbove(VISITOR_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.findByVisitorUserIdAndOrganizationIdAndDeletedAtIsNullOrderByVisitedAtDesc(
                    VISITOR_USER, ORG_ID))
                    .thenReturn(List.of(v));

            // when
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByWatcher(ORG_ID, VISITOR_USER, VISITOR_USER);

            // then
            assertThat(list).hasSize(1);
        }
    }

    // ─── getVisitsByResident ──────────────────────────────────────────

    @Nested
    @DisplayName("getVisitsByResident")
    class GetVisitsByResident {

        @Test
        @DisplayName("ADMIN は居住者の訪問記録一覧を取得できる")
        void adminCanGetByResident() {
            // given
            MonitoringCommitteeVisit v = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.findByResidentRegistryIdAndDeletedAtIsNullOrderByVisitedAtDesc(REGISTRY_ID))
                    .thenReturn(List.of(v));

            // when
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByResident(ORG_ID, REGISTRY_ID, ADMIN_USER);

            // then
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getResidentRegistryId()).isEqualTo(REGISTRY_ID);
        }

        @Test
        @DisplayName("非 ADMIN は SNAPSHOT_ACCESS_FORBIDDEN")
        void nonAdminForbidden() {
            // given
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION")).thenReturn(false);

            // when/then
            assertThatThrownBy(() -> service.getVisitsByResident(ORG_ID, REGISTRY_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("他組織の訪問記録はフィルタリングされる")
        void filtersByOrganization() {
            // given
            Long otherOrgId = 999L;
            MonitoringCommitteeVisit sameOrg = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            MonitoringCommitteeVisit otherOrg = buildVisit(UUID.randomUUID(), LocalDateTime.now());
            setField(otherOrg, "organizationId", otherOrgId);

            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION")).thenReturn(true);
            when(visitRepo.findByResidentRegistryIdAndDeletedAtIsNullOrderByVisitedAtDesc(REGISTRY_ID))
                    .thenReturn(List.of(sameOrg, otherOrg));

            // when
            List<MonitoringCommitteeVisitDto> list = service.getVisitsByResident(ORG_ID, REGISTRY_ID, ADMIN_USER);

            // then: 他組織はフィルタリングされる
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getOrganizationId()).isEqualTo(ORG_ID);
        }
    }
}
