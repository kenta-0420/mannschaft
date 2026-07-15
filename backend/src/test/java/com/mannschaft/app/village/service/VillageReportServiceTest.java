package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.ReportCreateRequest;
import com.mannschaft.app.village.dto.ReportResolveRequest;
import com.mannschaft.app.village.dto.ReportResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageReportEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.entity.enums.VillageReportTargetType;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageReportRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageReportService} 単体テスト（F17.1 Phase 1 B7）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>通報作成（村人が PENDING で作成）</li>
 *   <li>非村人による通報拒否</li>
 *   <li>レートリミット（11 件目で 429 / VILLAGE_041）</li>
 *   <li>通報者非開示（reporterDisplayName=ANONYMOUS_VILLAGER 固定）</li>
 *   <li>HEADMAN による resolve（RESOLVED）</li>
 *   <li>ELDER による resolve（RESOLVED）</li>
 *   <li>非権限ユーザー（VILLAGER）による resolve 拒否（VILLAGE_024）</li>
 *   <li>非メンバーによる resolve 拒否（VILLAGE_024）</li>
 *   <li>actionTaken=BANNED → membershipService.ban 呼び出し確認</li>
 *   <li>二重 resolve 拒否（VILLAGE_042）</li>
 *   <li>存在しない report の resolve（VILLAGE_040）</li>
 *   <li>村跨ぎ IDOR 防止（別村の report ID 指定で VILLAGE_040）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageReportService 単体テスト")
class VillageReportServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000ff");
    private static final UUID REPORT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000aaa");
    private static final UUID HEADMAN_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b01");
    private static final UUID ELDER_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b02");
    private static final UUID VILLAGER_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b03");
    private static final UUID TARGET_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000c01");
    private static final Long REPORTER_USER_ID = 100L;
    private static final Long HEADMAN_USER_ID = 200L;
    private static final Long ELDER_USER_ID = 201L;
    private static final Long VILLAGER_USER_ID = 202L;
    private static final Long NON_MEMBER_USER_ID = 999L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageReportRepository reportRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageMembershipService membershipService;

    @InjectMocks
    private VillageReportService service;

    private VillageEntity village;

    @BeforeEach
    void setUp() {
        village = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        village.setId(VILLAGE_ID);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageMembershipEntity memberOf(UUID id, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        m.setId(id);
        return m;
    }

    private VillageReportEntity existingReport(VillageReportStatus status) {
        VillageReportEntity r = VillageReportEntity.builder()
                .villageId(VILLAGE_ID)
                .reporterUserId(REPORTER_USER_ID)
                .targetType(VillageReportTargetType.POST)
                .targetRefId("bulletin_post:01234567")
                .reasonCode("harassment")
                .detail("迷惑投稿")
                .status(status)
                .createdAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .build();
        r.setId(REPORT_ID);
        return r;
    }

    private ReportCreateRequest validCreateRequest() {
        return new ReportCreateRequest(
                VillageReportTargetType.POST,
                "bulletin_post:01234567",
                "harassment",
                "迷惑投稿");
    }

    // ========================================================================
    // 通報作成
    // ========================================================================

    @Test
    @DisplayName("通報作成: 村人が PENDING で通報を作成できる（通報者は ANONYMOUS_VILLAGER でマスク）")
    void createReport_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, REPORTER_USER_ID))
                .willReturn(Optional.of(memberOf(VILLAGER_MEMBERSHIP_ID, REPORTER_USER_ID, VillageRole.VILLAGER)));
        given(reportRepository.countByReporterUserIdAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                .willReturn(0L);
        given(reportRepository.save(any(VillageReportEntity.class)))
                .willAnswer(inv -> {
                    VillageReportEntity e = inv.getArgument(0);
                    e.setId(REPORT_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });

        ReportResponse res = service.createReport(VILLAGE_ID, REPORTER_USER_ID, validCreateRequest());

        assertThat(res.status()).isEqualTo(VillageReportStatus.PENDING);
        assertThat(res.reporterDisplayName()).isEqualTo("ANONYMOUS_VILLAGER");
        assertThat(res.targetType()).isEqualTo(VillageReportTargetType.POST);
        assertThat(res.reasonCode()).isEqualTo("harassment");
    }

    @Test
    @DisplayName("通報作成: 非村人は VILLAGE_007（NOT_MEMBER）で拒否")
    void createReport_nonMember_denied() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, NON_MEMBER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReport(VILLAGE_ID, NON_MEMBER_USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("通報作成: 直近 1 時間に 10 件達していると 11 件目は VILLAGE_009（RATE_LIMITED）")
    void createReport_rateLimit_exceeded() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, REPORTER_USER_ID))
                .willReturn(Optional.of(memberOf(VILLAGER_MEMBERSHIP_ID, REPORTER_USER_ID, VillageRole.VILLAGER)));
        given(reportRepository.countByReporterUserIdAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                .willReturn(10L);

        assertThatThrownBy(() -> service.createReport(VILLAGE_ID, REPORTER_USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_REPORT_RATE_LIMITED);
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("通報作成: 9 件まではレートリミットに掛からない（境界値）")
    void createReport_rateLimit_underThreshold() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, REPORTER_USER_ID))
                .willReturn(Optional.of(memberOf(VILLAGER_MEMBERSHIP_ID, REPORTER_USER_ID, VillageRole.VILLAGER)));
        given(reportRepository.countByReporterUserIdAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                .willReturn(9L);
        given(reportRepository.save(any(VillageReportEntity.class)))
                .willAnswer(inv -> {
                    VillageReportEntity e = inv.getArgument(0);
                    e.setId(REPORT_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });

        ReportResponse res = service.createReport(VILLAGE_ID, REPORTER_USER_ID, validCreateRequest());

        assertThat(res.status()).isEqualTo(VillageReportStatus.PENDING);
        verify(reportRepository, times(1)).save(any());
    }

    // ========================================================================
    // 通報一覧
    // ========================================================================

    @Test
    @DisplayName("通報一覧: HEADMAN は status 指定で取得でき、通報者は ANONYMOUS_VILLAGER 固定")
    void listReports_byHeadman_withStatus() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN)));
        Page<VillageReportEntity> page = new PageImpl<>(List.of(existingReport(VillageReportStatus.PENDING)));
        given(reportRepository.findByVillageIdAndStatus(
                eq(VILLAGE_ID), eq(VillageReportStatus.PENDING), any(Pageable.class)))
                .willReturn(page);

        List<ReportResponse> res = service.listReports(
                VILLAGE_ID, HEADMAN_USER_ID, VillageReportStatus.PENDING, 0, 50);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).reporterDisplayName()).isEqualTo("ANONYMOUS_VILLAGER");
        assertThat(res.get(0).status()).isEqualTo(VillageReportStatus.PENDING);
    }

    @Test
    @DisplayName("通報一覧: status 未指定で全件（findByVillageIdOrderByCreatedAtDesc 経路）")
    void listReports_withoutStatus_callsAllOrderByCreatedAtDesc() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.of(memberOf(ELDER_MEMBERSHIP_ID, ELDER_USER_ID, VillageRole.ELDER)));
        Page<VillageReportEntity> page = new PageImpl<>(List.of(existingReport(VillageReportStatus.RESOLVED)));
        given(reportRepository.findByVillageIdOrderByCreatedAtDesc(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(page);

        List<ReportResponse> res = service.listReports(VILLAGE_ID, ELDER_USER_ID, null, 0, 50);

        assertThat(res).hasSize(1);
        verify(reportRepository, times(1)).findByVillageIdOrderByCreatedAtDesc(eq(VILLAGE_ID), any());
        verify(reportRepository, never()).findByVillageIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("通報一覧: VILLAGER（非モデレーター）は VILLAGE_024 で拒否")
    void listReports_byVillager_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.of(memberOf(VILLAGER_MEMBERSHIP_ID, VILLAGER_USER_ID, VillageRole.VILLAGER)));

        assertThatThrownBy(() -> service.listReports(VILLAGE_ID, VILLAGER_USER_ID, null, 0, 50))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ========================================================================
    // 通報解決
    // ========================================================================

    @Test
    @DisplayName("通報解決: HEADMAN が RESOLVED へ遷移でき handler_membership_id が記録される")
    void resolveReport_byHeadman_success() {
        VillageMembershipEntity headman = memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(headman));
        VillageReportEntity report = existingReport(VillageReportStatus.PENDING);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(reportRepository.save(any(VillageReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.CONTENT_REMOVED,
                "投稿削除済");

        ReportResponse res = service.resolveReport(VILLAGE_ID, REPORT_ID, HEADMAN_USER_ID, req);

        assertThat(res.status()).isEqualTo(VillageReportStatus.RESOLVED);
        assertThat(res.handlerAction()).isEqualTo("CONTENT_REMOVED");
        assertThat(res.handledAt()).isNotNull();
        assertThat(report.getHandlerMembershipId()).isEqualTo(HEADMAN_MEMBERSHIP_ID);
    }

    @Test
    @DisplayName("通報解決: ELDER も RESOLVED へ遷移できる")
    void resolveReport_byElder_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ELDER_USER_ID))
                .willReturn(Optional.of(memberOf(ELDER_MEMBERSHIP_ID, ELDER_USER_ID, VillageRole.ELDER)));
        given(reportRepository.findById(REPORT_ID))
                .willReturn(Optional.of(existingReport(VillageReportStatus.PENDING)));
        given(reportRepository.save(any(VillageReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.DISMISSED,
                VillageReportService.ReportActionTaken.NONE,
                "誤通報");

        ReportResponse res = service.resolveReport(VILLAGE_ID, REPORT_ID, ELDER_USER_ID, req);

        assertThat(res.status()).isEqualTo(VillageReportStatus.DISMISSED);
        assertThat(res.handlerAction()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("通報解決: VILLAGER は VILLAGE_024 で拒否される")
    void resolveReport_byVillager_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, VILLAGER_USER_ID))
                .willReturn(Optional.of(memberOf(VILLAGER_MEMBERSHIP_ID, VILLAGER_USER_ID, VillageRole.VILLAGER)));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.NONE,
                null);

        assertThatThrownBy(() -> service.resolveReport(VILLAGE_ID, REPORT_ID, VILLAGER_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        verify(reportRepository, never()).findById(any());
    }

    @Test
    @DisplayName("通報解決: actionTaken=BANNED かつ MEMBERSHIP 通報 → membershipService.ban が呼ばれる")
    void resolveReport_banned_callsMembershipServiceBan() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN)));

        VillageReportEntity report = VillageReportEntity.builder()
                .villageId(VILLAGE_ID)
                .reporterUserId(REPORTER_USER_ID)
                .targetType(VillageReportTargetType.MEMBERSHIP)
                .targetRefId(TARGET_MEMBERSHIP_ID.toString())
                .reasonCode("harassment")
                .status(VillageReportStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .build();
        report.setId(REPORT_ID);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(reportRepository.save(any(VillageReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.BANNED,
                "悪質ユーザー");

        service.resolveReport(VILLAGE_ID, REPORT_ID, HEADMAN_USER_ID, req);

        verify(membershipService, times(1)).ban(
                eq(VILLAGE_ID),
                eq(TARGET_MEMBERSHIP_ID),
                eq(HEADMAN_USER_ID),
                any(MembershipBanRequest.class));
    }

    @Test
    @DisplayName("通報解決: 既に RESOLVED の通報は VILLAGE_042 で拒否される")
    void resolveReport_alreadyResolved() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN)));
        given(reportRepository.findById(REPORT_ID))
                .willReturn(Optional.of(existingReport(VillageReportStatus.RESOLVED)));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.NONE,
                null);

        assertThatThrownBy(() -> service.resolveReport(VILLAGE_ID, REPORT_ID, HEADMAN_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_REPORT_ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("通報解決: 存在しない report ID は VILLAGE_040")
    void resolveReport_notFound() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN)));
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.NONE,
                null);

        assertThatThrownBy(() -> service.resolveReport(VILLAGE_ID, REPORT_ID, HEADMAN_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("通報解決: 別村の report ID を指定 → VILLAGE_040（IDOR 防止）")
    void resolveReport_crossVillage_idor() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, HEADMAN_USER_ID))
                .willReturn(Optional.of(memberOf(HEADMAN_MEMBERSHIP_ID, HEADMAN_USER_ID, VillageRole.HEADMAN)));

        // 他村の通報
        VillageReportEntity foreign = existingReport(VillageReportStatus.PENDING);
        foreign.setVillageId(OTHER_VILLAGE_ID);
        given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(foreign));

        ReportResolveRequest req = new ReportResolveRequest(
                VillageReportStatus.RESOLVED,
                VillageReportService.ReportActionTaken.NONE,
                null);

        assertThatThrownBy(() -> service.resolveReport(VILLAGE_ID, REPORT_ID, HEADMAN_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_REPORT_NOT_FOUND);
        verify(membershipService, never()).ban(any(), any(), anyLong(), any());
    }
}
