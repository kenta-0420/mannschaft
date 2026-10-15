package com.mannschaft.app.property.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.property.service.PropertyWorkPackageService.WorkPackageRequest;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PropertyWorkPackageService} 単体テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>設計書 §3 / §5.2 / §5.4 / §6.6 / §5.7 を網羅:</p>
 * <ul>
 *   <li>create: 正常系（vendor あり/なし）+ 検証エラー（title 空・長さ超過・tag 上限超過・incident_date 必須）</li>
 *   <li>update: 正常系</li>
 *   <li>changeStatus / assignVendor / linkBudgetTransaction / attachDocument / detachDocument</li>
 *   <li>createFromIncident: 重複チェック（既存パッケージあれば skip）</li>
 *   <li>publishToTimeline: TimelinePostService.createPost 呼び出し検証（content 形式 / scopeType 写像）</li>
 *   <li>serializeTags / deserializeTags: List ↔ JSON 変換</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyWorkPackageService 単体テスト（F09.13 Phase 1-ζ-A）")
class PropertyWorkPackageServiceTest {

    @Mock
    private PropertyWorkPackageRepository packageRepository;
    @Mock
    private VendorService vendorService;
    @Mock
    private TimelinePostService timelinePostService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PropertyWorkPackageService service;

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORG = "ORGANIZATION";
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long USER_ID = 7L;
    private static final Long PACKAGE_ID = 555L;
    private static final Long VENDOR_ID = 11L;
    private static final Long INCIDENT_ID = 33L;

    @BeforeEach
    void setUp() {
        service = new PropertyWorkPackageService(
                packageRepository, vendorService, objectMapper, timelinePostService);
        // TimelinePost のデフォルト戻り値（呼び出されたテストで必要）
        // ※ Mockito strict なので必要なテスト側で個別に given(...) する
    }

    private WorkPackageRequest baseRequest(WorkType type) {
        return new WorkPackageRequest(
                type,
                "外壁塗装",
                "南側外壁大規模修繕",
                "築20年経過に伴う改修",
                null,                            // dwellingUnitId
                null,                            // incidentId
                type == WorkType.INCIDENT || type == WorkType.DISASTER
                        ? LocalDate.of(2026, 5, 1) : null,  // incidentDate
                null,                            // incidentNarrative
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 8, 31),
                null, null,
                null,                            // vendorId
                12_000_000L, 11_500_000L, null,
                "JPY",
                null,
                LocalDate.of(2031, 8, 31),
                Boolean.TRUE,
                WorkPackageVisibility.MEMBERS_MASKED,
                List.of("大規模修繕", "国交省ガイドライン準拠"));
    }

    private VendorEntity stubVendor(Long id, String scopeType, Long scopeId, String name) {
        VendorEntity v = VendorEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(USER_ID)
                .build();
        ReflectionTestUtils.setField(v, "id", id);
        return v;
    }

    private PropertyWorkPackageEntity stubPackage(Long id, String scopeType, Long scopeId) {
        PropertyWorkPackageEntity e = PropertyWorkPackageEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .workType(WorkType.RENOVATION)
                .title("既存タイトル")
                .currency("JPY")
                .visibility(WorkPackageVisibility.ADMINS_ONLY)
                .status(WorkPackageStatus.PLANNED)
                .attachmentCount(0)
                .commentCount(0)
                .isDisclosable(true)
                .createdBy(USER_ID)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private PostResponse stubPostResponse(Long id) {
        return PostResponse.builder()
                .id(id)
                .scope(new PostResponse.PostScopeDto("TEAM", TEAM_ID))
                .author(new PostResponse.PostAuthorDto(USER_ID, null, null, null))
                .content(new PostResponse.PostContentDto("content", null, null, "PUBLISHED", null, false))
                .stats(new PostResponse.PostStatsDto(0, 0, 0, (short) 0, (short) 0))
                .audit(new PostResponse.PostAuditDto(java.time.LocalDateTime.now(), java.time.LocalDateTime.now()))
                .build();
    }

    // =========================================================================
    // create
    // =========================================================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: vendor 未指定で TimelinePost 自動投稿が起こり保存される")
        void create_noVendor_success() {
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> {
                        PropertyWorkPackageEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", PACKAGE_ID);
                        return e;
                    });
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(900L));

            PropertyWorkPackageEntity saved = service.create(SCOPE_TEAM, TEAM_ID, USER_ID,
                    baseRequest(WorkType.RENOVATION));

            assertThat(saved.getScopeType()).isEqualTo(SCOPE_TEAM);
            assertThat(saved.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(saved.getStatus()).isEqualTo(WorkPackageStatus.PLANNED);
            assertThat(saved.getVisibility()).isEqualTo(WorkPackageVisibility.MEMBERS_MASKED);
            assertThat(saved.getCurrency()).isEqualTo("JPY");
            assertThat(saved.getTimelinePostId()).isEqualTo(900L);
            assertThat(saved.getTags()).contains("大規模修繕").contains("国交省ガイドライン準拠");

            ArgumentCaptor<CreatePostRequest> postCap = ArgumentCaptor.forClass(CreatePostRequest.class);
            verify(timelinePostService).createSystemPost(postCap.capture(), any(Long.class));
            assertThat(postCap.getValue().getContent()).contains("【物件履歴】南側外壁大規模修繕");
            assertThat(postCap.getValue().getContent()).contains("RENOVATION");
            assertThat(postCap.getValue().getScopeType()).isEqualTo("TEAM");
            // CreatePostRequest.scopeId は slug 文字列も受けるため String 化された（投稿400根治）。
            // システム内部経路では数値文字列として渡され、サービス側で内部 Long ID に parse される。
            assertThat(postCap.getValue().getScopeId()).isEqualTo(String.valueOf(TEAM_ID));
        }

        @Test
        @DisplayName("正常系: vendor あり → vendor_name_snapshot を保存し IDOR 検証として VendorService を経由")
        void create_withVendor_success() {
            VendorEntity vendor = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "○○塗装工業");
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor);
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> {
                        PropertyWorkPackageEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", PACKAGE_ID);
                        return e;
                    });
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(900L));

            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "工事案件", null, null, null, null, null,
                    null, null, null, null, VENDOR_ID,
                    null, null, null, "JPY", null, null, null,
                    WorkPackageVisibility.ADMINS_ONLY, null);

            PropertyWorkPackageEntity saved = service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req);

            assertThat(saved.getVendorId()).isEqualTo(VENDOR_ID);
            assertThat(saved.getVendorNameSnapshot()).isEqualTo("○○塗装工業");
            verify(vendorService).getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID);
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープでは TimelinePost も ORGANIZATION で起票される")
        void create_orgScope_timelineOrg() {
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(901L));

            service.create(SCOPE_ORG, ORG_ID, USER_ID, baseRequest(WorkType.RENOVATION));

            ArgumentCaptor<CreatePostRequest> cap = ArgumentCaptor.forClass(CreatePostRequest.class);
            verify(timelinePostService).createSystemPost(cap.capture(), any(Long.class));
            assertThat(cap.getValue().getScopeType()).isEqualTo("ORGANIZATION");
            // CreatePostRequest.scopeId は String 化された（投稿400根治）。数値文字列で渡る。
            assertThat(cap.getValue().getScopeId()).isEqualTo(String.valueOf(ORG_ID));
        }

        // F09.13 Phase 2-α-2: visibility による status 分岐
        @Test
        @DisplayName("正常系: visibility=ADMINS_ONLY のパッケージは TimelinePost を DRAFT で起票")
        void create_adminsOnly_timelineDraft() {
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(910L));

            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "管理者限定", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, "JPY", null, null, null,
                    WorkPackageVisibility.ADMINS_ONLY, null);

            service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req);

            ArgumentCaptor<CreatePostRequest> cap = ArgumentCaptor.forClass(CreatePostRequest.class);
            verify(timelinePostService).createSystemPost(cap.capture(), any(Long.class));
            assertThat(cap.getValue().getStatus()).isEqualTo(PostStatus.DRAFT);
        }

        @Test
        @DisplayName("正常系: visibility=MEMBERS_MASKED のパッケージは TimelinePost を PUBLISHED 相当（status=null）で起票")
        void create_membersMasked_timelinePublished() {
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(911L));

            // baseRequest は visibility=MEMBERS_MASKED
            service.create(SCOPE_TEAM, TEAM_ID, USER_ID, baseRequest(WorkType.RENOVATION));

            ArgumentCaptor<CreatePostRequest> cap = ArgumentCaptor.forClass(CreatePostRequest.class);
            verify(timelinePostService).createSystemPost(cap.capture(), any(Long.class));
            // ADMINS_ONLY 以外は status=null（TimelinePostService 側で PUBLISHED に解決）
            assertThat(cap.getValue().getStatus()).isNull();
        }

        @Test
        @DisplayName("正常系: visibility=MEMBERS_ONLY のパッケージは TimelinePost を PUBLISHED 相当で起票")
        void create_membersOnly_timelinePublished() {
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(912L));

            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "メンバー公開", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, "JPY", null, null, null,
                    WorkPackageVisibility.MEMBERS_ONLY, null);

            service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req);

            ArgumentCaptor<CreatePostRequest> cap = ArgumentCaptor.forClass(CreatePostRequest.class);
            verify(timelinePostService).createSystemPost(cap.capture(), any(Long.class));
            assertThat(cap.getValue().getStatus()).isNull();
        }

        @Test
        @DisplayName("異常系: title が空白なら PROPERTY_004")
        void create_blankTitle_throws() {
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "  ", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    WorkPackageVisibility.ADMINS_ONLY, null);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: title が 200 文字超過なら PROPERTY_004")
        void create_titleTooLong_throws() {
            String longTitle = "あ".repeat(201);
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, longTitle, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: tags が 21 件以上なら PROPERTY_004")
        void create_tooManyTags_throws() {
            List<String> tags = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) tags.add("tag" + i);
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "ok", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, tags);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: 各 tag が 30 文字超過なら PROPERTY_004")
        void create_tagTooLong_throws() {
            String longTag = "あ".repeat(31);
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "ok", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    List.of(longTag));
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: INCIDENT で incident_date が null なら PROPERTY_004")
        void create_incidentWithoutDate_throws() {
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.INCIDENT, null, "事故", null, null, null,
                    null,  // incidentDate 欠落
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    WorkPackageVisibility.ADMINS_ONLY, null);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: 金額が負数なら PROPERTY_004")
        void create_negativeAmount_throws() {
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "ok", null, null, null, null, null,
                    null, null, null, null, null,
                    -1L, null, null, "JPY", null, null, null, null, null);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: actualStartDate > actualEndDate なら PROPERTY_004")
        void create_dateOrderInvalid_throws() {
            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "ok", null, null, null, null, null,
                    null, null,
                    LocalDate.of(2026, 8, 31), LocalDate.of(2026, 6, 1),
                    null, null, null, null, "JPY", null, null, null, null, null);
            assertThatThrownBy(() -> service.create(SCOPE_TEAM, TEAM_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    // =========================================================================
    // update
    // =========================================================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: title/description/visibility/tags を上書き保存")
        void update_success() {
            PropertyWorkPackageEntity existing = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(existing));
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            WorkPackageRequest req = baseRequest(WorkType.RENOVATION);
            PropertyWorkPackageEntity saved = service.update(PACKAGE_ID, USER_ID, req);

            assertThat(saved.getTitle()).isEqualTo("南側外壁大規模修繕");
            assertThat(saved.getDescription()).contains("築20年経過");
            assertThat(saved.getVisibility()).isEqualTo(WorkPackageVisibility.MEMBERS_MASKED);
            assertThat(saved.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("異常系: 不在 ID は PROPERTY_001")
        void update_notFound_throws() {
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.update(PACKAGE_ID, USER_ID, baseRequest(WorkType.RENOVATION)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_001);
        }

        @Test
        @DisplayName("正常系: vendor 変更で snapshot も更新（VendorService 経由 IDOR チェック）")
        void update_changeVendor_success() {
            PropertyWorkPackageEntity existing = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
            VendorEntity newVendor = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "新業者");
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(existing));
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(newVendor);
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            WorkPackageRequest req = new WorkPackageRequest(
                    WorkType.RENOVATION, null, "ok", null, null, null, null, null,
                    null, null, null, null, VENDOR_ID,
                    null, null, null, "JPY", null, null, null, null, null);

            PropertyWorkPackageEntity saved = service.update(PACKAGE_ID, USER_ID, req);
            assertThat(saved.getVendorId()).isEqualTo(VENDOR_ID);
            assertThat(saved.getVendorNameSnapshot()).isEqualTo("新業者");
        }
    }

    // =========================================================================
    // changeStatus / softDelete / assignVendor / linkBudgetTransaction / attach/detach
    // =========================================================================

    @Test
    @DisplayName("changeStatus: ステータスを上書きして saved を返す")
    void changeStatus_success() {
        PropertyWorkPackageEntity existing = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                .willReturn(Optional.of(existing));
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.changeStatus(
                PACKAGE_ID, USER_ID, WorkPackageStatus.IN_PROGRESS);
        assertThat(saved.getStatus()).isEqualTo(WorkPackageStatus.IN_PROGRESS);
        assertThat(saved.getUpdatedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("changeStatus: status null は PROPERTY_004")
    void changeStatus_null_throws() {
        assertThatThrownBy(() -> service.changeStatus(PACKAGE_ID, USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
    }

    @Test
    @DisplayName("assignVendor: vendor 一致時に snapshot を更新")
    void assignVendor_success() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        VendorEntity vendor = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "業者X");
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor);
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.assignVendor(PACKAGE_ID, VENDOR_ID);
        assertThat(saved.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(saved.getVendorNameSnapshot()).isEqualTo("業者X");
    }

    @Test
    @DisplayName("assignVendor: null 指定で snapshot もクリア")
    void assignVendor_null_clearsSnapshot() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        pkg.assignVendor(VENDOR_ID, "旧業者");
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.assignVendor(PACKAGE_ID, null);
        assertThat(saved.getVendorId()).isNull();
        assertThat(saved.getVendorNameSnapshot()).isNull();
    }

    @Test
    @DisplayName("linkBudgetTransaction: actualAmountCache を反映")
    void linkBudgetTransaction_success() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.linkBudgetTransaction(PACKAGE_ID, 88L, 1_000_000L);
        assertThat(saved.getBudgetTransactionId()).isEqualTo(88L);
        assertThat(saved.getActualAmount()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("attachDocument: count を増加")
    void attachDocument_success() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.attachDocument(PACKAGE_ID);
        assertThat(saved.getAttachmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("attachDocument: 上限 50 件超過で PROPERTY_009")
    void attachDocument_overLimit_throws() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        // 50 件まで増やしておく
        for (int i = 0; i < 50; i++) pkg.incrementAttachmentCount();
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));

        assertThatThrownBy(() -> service.attachDocument(PACKAGE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_009);
    }

    @Test
    @DisplayName("detachDocument: count を減算")
    void detachDocument_success() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        pkg.incrementAttachmentCount();
        pkg.incrementAttachmentCount();
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PropertyWorkPackageEntity saved = service.detachDocument(PACKAGE_ID);
        assertThat(saved.getAttachmentCount()).isEqualTo(1);
    }

    // =========================================================================
    // createFromIncident
    // =========================================================================

    @Nested
    @DisplayName("createFromIncident")
    class CreateFromIncident {

        @Test
        @DisplayName("正常系: 既存パッケージ無しなら新規作成して TimelinePost も投稿")
        void createFromIncident_new_success() {
            given(packageRepository.findByIncidentIdAndDeletedAtIsNull(INCIDENT_ID))
                    .willReturn(Optional.empty());
            given(packageRepository.save(any(PropertyWorkPackageEntity.class)))
                    .willAnswer(inv -> {
                        PropertyWorkPackageEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", PACKAGE_ID);
                        return e;
                    });
            given(timelinePostService.createSystemPost(any(CreatePostRequest.class), any(Long.class)))
                    .willReturn(stubPostResponse(902L));

            Optional<PropertyWorkPackageEntity> created = service.createFromIncident(
                    INCIDENT_ID, SCOPE_TEAM, TEAM_ID, USER_ID,
                    "事故報告", LocalDate.of(2026, 5, 1), "事故の経緯");

            assertThat(created).isPresent();
            assertThat(created.get().getWorkType()).isEqualTo(WorkType.INCIDENT);
            assertThat(created.get().getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(created.get().getStatus()).isEqualTo(WorkPackageStatus.PLANNED);
            verify(timelinePostService, times(1)).createSystemPost(any(), any());
        }

        @Test
        @DisplayName("重複検知: 既存パッケージあれば skip して empty を返す")
        void createFromIncident_duplicate_skip() {
            PropertyWorkPackageEntity existing = stubPackage(999L, SCOPE_TEAM, TEAM_ID);
            given(packageRepository.findByIncidentIdAndDeletedAtIsNull(INCIDENT_ID))
                    .willReturn(Optional.of(existing));

            Optional<PropertyWorkPackageEntity> result = service.createFromIncident(
                    INCIDENT_ID, SCOPE_TEAM, TEAM_ID, USER_ID,
                    "事故", LocalDate.of(2026, 5, 1), null);

            assertThat(result).isEmpty();
            verify(packageRepository, never()).save(any());
            verify(timelinePostService, never()).createSystemPost(any(), any());
        }
    }

    // =========================================================================
    // tags JSON シリアライズ
    // =========================================================================

    @Test
    @DisplayName("deserializeTags: 保存された JSON が List<String> に戻る")
    void deserializeTags_success() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        pkg.updateTags("[\"a\",\"b\",\"c\"]");
        List<String> tags = service.deserializeTags(pkg);
        assertThat(tags).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("deserializeTags: null/空文字列は空リスト")
    void deserializeTags_blank() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        pkg.updateTags(null);
        assertThat(service.deserializeTags(pkg)).isEmpty();
        pkg.updateTags("");
        assertThat(service.deserializeTags(pkg)).isEmpty();
    }

    @Test
    @DisplayName("deserializeTags: 壊れた JSON は fail-safe で空リスト")
    void deserializeTags_invalid() {
        PropertyWorkPackageEntity pkg = stubPackage(PACKAGE_ID, SCOPE_TEAM, TEAM_ID);
        pkg.updateTags("not-json");
        assertThat(service.deserializeTags(pkg)).isEmpty();
    }
}
