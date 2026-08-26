package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentPresignRequest;
import com.mannschaft.app.bulletin.dto.AttachmentPresignResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CreateAttachmentRequest;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinAttachmentRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinAttachmentService} の単体テスト（Mockito）。
 *
 * <p>presign / 確定 / 一覧 / download-url / 削除の各認可分岐（本人・モデレーター・非メンバー 403・
 * 他スコープ）、件数上限、MIME / サイズ不正、THREAD / REPLY 両対象、ORG / TEAM / VILLAGE /
 * PERSONAL を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinAttachmentService 単体テスト")
class BulletinAttachmentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long ATTACHMENT_ID = 300L;
    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Mock
    private BulletinAttachmentRepository attachmentRepository;
    @Mock
    private BulletinThreadRepository threadRepository;
    @Mock
    private BulletinReplyRepository replyRepository;
    @Mock
    private BulletinMapper bulletinMapper;
    @Mock
    private BulletinAccessGuard accessGuard;
    @Mock
    private VillageBulletinAccessService villageBulletinAccessService;
    @Mock
    private StorageQuotaService storageQuotaService;
    @Mock
    private StorageService storageService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private com.mannschaft.app.tournament.service.TournamentContactAccessService tournamentContactAccessService;

    @InjectMocks
    private BulletinAttachmentService service;

    // ─── ヘルパ ───

    private BulletinThreadEntity teamThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.TEAM).scopeId(TEAM_ID).authorId(USER_ID).build();
    }

    private BulletinThreadEntity orgThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.ORGANIZATION).scopeId(ORG_ID).authorId(USER_ID).build();
    }

    private BulletinThreadEntity villageThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.VILLAGE).scopeId(0L)
                .scopeVillageId(VILLAGE_ID).authorId(USER_ID).build();
    }

    private BulletinThreadEntity personalThread(Long ownerId) {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.PERSONAL).scopeId(ownerId).authorId(ownerId).build();
    }

    private AttachmentPresignRequest presignReq(TargetType type, Long targetId) {
        return new AttachmentPresignRequest(type, targetId, "doc.pdf", "application/pdf", 1024L);
    }

    private CreateAttachmentRequest createReq(TargetType type, Long targetId) {
        return new CreateAttachmentRequest(type, targetId, "bulletin/k", "doc.pdf", 1024L, "application/pdf");
    }

    private BulletinAttachmentEntity attachment(Long createdBy) {
        return BulletinAttachmentEntity.builder()
                .id(ATTACHMENT_ID).targetType(TargetType.THREAD).targetId(THREAD_ID)
                .fileKey("bulletin/key").originalFilename("doc.pdf").fileSize(1024L)
                .contentType("application/pdf").createdBy(createdBy).build();
    }

    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("presign（アップロード URL 発行）")
    class Presign {

        @Test
        @DisplayName("TEAM スレッドにメンバーが presign 発行できる")
        void teamThreadMember() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of());
            given(storageService.generateUploadUrl(any(), eq("application/pdf"), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://r2/put", "k", 900L));

            AttachmentPresignResponse res = service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), USER_ID);

            assertThat(res.uploadUrl()).isEqualTo("https://r2/put");
            assertThat(res.fileKey()).startsWith("bulletin/TEAM/" + TEAM_ID + "/THREAD/" + THREAD_ID + "/");
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, TEAM_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.TEAM, TEAM_ID, 1024L);
        }

        @Test
        @DisplayName("REPLY 対象は返信→スレッドを逆引きして認可する")
        void replyTargetReverseLookup() {
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).authorId(USER_ID).build();
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.REPLY, REPLY_ID))
                    .willReturn(List.of());
            given(storageService.generateUploadUrl(any(), any(), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://r2/put", "k", 900L));

            AttachmentPresignResponse res = service.generateUploadUrl(presignReq(TargetType.REPLY, REPLY_ID), USER_ID);

            assertThat(res.fileKey()).contains("/REPLY/" + REPLY_ID + "/");
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("非メンバーは 403（COMMON_002）— presign 不可")
        void nonMemberForbidden() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(OTHER_USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThatThrownBy(() -> service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(storageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("VILLAGE は村閲覧認可（MEMBERS_ONLY 非メンバーは VILLAGE_BULLETIN_VIEW_FORBIDDEN）")
        void villageNonMemberForbidden() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread()));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, OTHER_USER_ID);

            assertThatThrownBy(() -> service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);
        }

        @Test
        @DisplayName("VILLAGE のクォータは操作者の PERSONAL スコープに計上され、fileKey に村UUIDを含む")
        void villageQuotaFallsBackToPersonal() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of());
            given(storageService.generateUploadUrl(any(), any(), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://r2/put", "k", 900L));

            AttachmentPresignResponse res = service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), USER_ID);

            assertThat(res.fileKey()).startsWith("bulletin/VILLAGE/" + VILLAGE_ID + "/");
            verify(storageQuotaService).checkQuota(StorageScopeType.PERSONAL, USER_ID, 1024L);
        }

        @Test
        @DisplayName("MIME ホワイトリスト外は ATTACHMENT_INVALID_CONTENT_TYPE")
        void invalidMime() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            AttachmentPresignRequest req =
                    new AttachmentPresignRequest(TargetType.THREAD, THREAD_ID, "x.svg", "image/svg+xml", 100L);

            assertThatThrownBy(() -> service.generateUploadUrl(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_INVALID_CONTENT_TYPE);
        }

        @Test
        @DisplayName("サイズ上限超過は ATTACHMENT_SIZE_EXCEEDED")
        void sizeExceeded() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            AttachmentPresignRequest req = new AttachmentPresignRequest(
                    TargetType.THREAD, THREAD_ID, "big.pdf", "application/pdf",
                    BulletinAttachmentService.MAX_FILE_SIZE_BYTES + 1);

            assertThatThrownBy(() -> service.generateUploadUrl(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }

        @Test
        @DisplayName("件数上限（5）到達は ATTACHMENT_LIMIT_EXCEEDED")
        void countExceeded() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of(attachment(USER_ID), attachment(USER_ID), attachment(USER_ID),
                            attachment(USER_ID), attachment(USER_ID)));

            assertThatThrownBy(() -> service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("対象スレッドが存在しない場合は ATTACHMENT_TARGET_NOT_FOUND")
        void targetNotFound() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("確定（メタデータ登録）")
    class Confirm {

        @Test
        @DisplayName("ORG スレッドにメンバーが確定でき、recordUpload が発火する")
        void orgConfirm() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(orgThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of());
            BulletinAttachmentEntity saved = attachment(USER_ID);
            given(attachmentRepository.save(any())).willReturn(saved);
            given(bulletinMapper.toAttachmentResponse(saved)).willReturn(
                    new AttachmentResponse(ATTACHMENT_ID, "THREAD", THREAD_ID, "bulletin/key",
                            "doc.pdf", 1024L, "application/pdf", USER_ID, null));

            AttachmentResponse res = service.confirmAttachment(createReq(TargetType.THREAD, THREAD_ID), USER_ID);

            assertThat(res.getId()).isEqualTo(ATTACHMENT_ID);
            verify(accessGuard).checkMembership(USER_ID, ScopeType.ORGANIZATION, ORG_ID);
            verify(storageQuotaService).recordUpload(
                    eq(StorageScopeType.ORGANIZATION), eq(ORG_ID), eq(1024L),
                    eq(StorageFeatureType.BULLETIN), any(), eq(ATTACHMENT_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("非メンバーの確定は 403 で save されない")
        void confirmNonMemberForbidden() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(OTHER_USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThatThrownBy(() -> service.confirmAttachment(createReq(TargetType.THREAD, THREAD_ID), OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(attachmentRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("一覧取得")
    class ListAttachments {

        @Test
        @DisplayName("スレッド一覧は閲覧認可後に取得する")
        void listThread() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of(attachment(USER_ID)));
            given(bulletinMapper.toAttachmentResponseList(any())).willReturn(List.of(
                    new AttachmentResponse(ATTACHMENT_ID, "THREAD", THREAD_ID, "k", "doc.pdf", 1024L,
                            "application/pdf", USER_ID, null)));

            List<AttachmentResponse> res = service.listThreadAttachments(THREAD_ID, USER_ID);

            assertThat(res).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("返信一覧は返信→スレッド逆引きで閲覧認可する")
        void listReply() {
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).authorId(USER_ID).build();
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(orgThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.REPLY, REPLY_ID))
                    .willReturn(List.of());
            given(bulletinMapper.toAttachmentResponseList(any())).willReturn(List.of());

            service.listReplyAttachments(REPLY_ID, USER_ID);

            verify(accessGuard).checkMembership(USER_ID, ScopeType.ORGANIZATION, ORG_ID);
        }
    }

    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("download-url 発行")
    class DownloadUrl {

        @Test
        @DisplayName("閲覧認可後に短命 GET URL を返し、生 fileKey は返さない")
        void downloadUrlSuccess() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment(USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            given(storageService.generateDownloadUrl(eq("bulletin/key"), any(Duration.class)))
                    .willReturn("https://r2/get?sig=x");

            AttachmentDownloadUrlResponse res = service.generateDownloadUrl(ATTACHMENT_ID, USER_ID);

            assertThat(res.downloadUrl()).isEqualTo("https://r2/get?sig=x");
            assertThat(res.expiresInSeconds()).isPositive();
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("存在しない添付は ATTACHMENT_NOT_FOUND")
        void notFound() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateDownloadUrl(ATTACHMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(BulletinErrorCode.ATTACHMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("非メンバーの download-url は 403")
        void downloadForbidden() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment(USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(OTHER_USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThatThrownBy(() -> service.generateDownloadUrl(ATTACHMENT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }
    }

    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("削除")
    class Delete {

        @Test
        @DisplayName("本人は削除でき、R2 削除・recordDeletion・監査ログが発火する")
        void deleteByOwner() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment(USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));

            service.deleteAttachment(ATTACHMENT_ID, USER_ID);

            verify(attachmentRepository).delete(any());
            verify(storageService).delete("bulletin/key");
            verify(storageQuotaService).recordDeletion(
                    eq(StorageScopeType.TEAM), eq(TEAM_ID), eq(1024L),
                    eq(StorageFeatureType.BULLETIN), any(), eq(ATTACHMENT_ID), eq(USER_ID));
            verify(auditLogService).record(any(), eq(USER_ID), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("TEAM の他者添付は ADMIN/DEPUTY のみ削除可（非管理者は 403）")
        void teamDeleteNonOwnerNonAdminForbidden() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment(USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkOwnerOrAdmin(OTHER_USER_ID, USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThatThrownBy(() -> service.deleteAttachment(ATTACHMENT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(attachmentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("VILLAGE の他者添付は村モデレーターのみ削除可（非モデレーターは 403）")
        void villageDeleteNonModeratorForbidden() {
            BulletinAttachmentEntity att = BulletinAttachmentEntity.builder()
                    .id(ATTACHMENT_ID).targetType(TargetType.THREAD).targetId(THREAD_ID)
                    .fileKey("k").fileSize(1024L).createdBy(USER_ID).build();
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(att));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread()));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, OTHER_USER_ID);

            assertThatThrownBy(() -> service.deleteAttachment(ATTACHMENT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
            verify(attachmentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("VILLAGE の他者添付を村モデレーターが削除できる")
        void villageDeleteByModerator() {
            BulletinAttachmentEntity att = BulletinAttachmentEntity.builder()
                    .id(ATTACHMENT_ID).targetType(TargetType.THREAD).targetId(THREAD_ID)
                    .fileKey("k").fileSize(1024L).createdBy(USER_ID).build();
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(att));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread()));

            service.deleteAttachment(ATTACHMENT_ID, OTHER_USER_ID);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, OTHER_USER_ID);
            verify(attachmentRepository).delete(att);
            // VILLAGE は作成者の PERSONAL スコープへ減算（upload と対称）
            verify(storageQuotaService).recordDeletion(
                    eq(StorageScopeType.PERSONAL), eq(USER_ID), eq(1024L),
                    eq(StorageFeatureType.BULLETIN), any(), eq(ATTACHMENT_ID), eq(OTHER_USER_ID));
        }

        @Test
        @DisplayName("PERSONAL は本人以外は削除不可（403）")
        void personalDeleteNonOwnerForbidden() {
            BulletinAttachmentEntity att = BulletinAttachmentEntity.builder()
                    .id(ATTACHMENT_ID).targetType(TargetType.THREAD).targetId(THREAD_ID)
                    .fileKey("k").fileSize(1024L).createdBy(USER_ID).build();
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(att));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(personalThread(USER_ID)));

            assertThatThrownBy(() -> service.deleteAttachment(ATTACHMENT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(attachmentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("R2 削除失敗でもベストエフォートで処理は継続する")
        void deleteR2FailureBestEffort() {
            given(attachmentRepository.findById(ATTACHMENT_ID)).willReturn(Optional.of(attachment(USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(teamThread()));
            doThrow(new RuntimeException("R2 down")).when(storageService).delete("bulletin/key");

            service.deleteAttachment(ATTACHMENT_ID, USER_ID);

            verify(attachmentRepository).delete(any());
            verify(auditLogService).record(any(), eq(USER_ID), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // F08.7.1 大会/ディビジョン連絡スコープ（B2）
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("F08.7.1 大会連絡スコープ認可配線")
    class TournamentScope {

        private static final Long T_SCOPE_ID = 500L;

        private BulletinThreadEntity tournamentThread() {
            return BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TOURNAMENT).scopeId(T_SCOPE_ID).authorId(USER_ID).build();
        }

        @Test
        @DisplayName("一覧取得: TOURNAMENT は canView を呼び checkMembership に落ちない")
        void listAttachments大会はcanView() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of());
            given(bulletinMapper.toAttachmentResponseList(any())).willReturn(List.of());

            service.listThreadAttachments(THREAD_ID, USER_ID);

            verify(tournamentContactAccessService).checkView(
                    eq(com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT),
                    eq(T_SCOPE_ID),
                    eq(com.mannschaft.app.tournament.ContactSpaceKind.BULLETIN),
                    eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("presign: TOURNAMENT は canPost を要求し checkMembership に落ちない")
        void presign大会はcanPost() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));
            given(attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, THREAD_ID))
                    .willReturn(List.of());
            given(storageService.generateUploadUrl(any(), any(), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://r2/put", "k", 900L));

            service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), USER_ID);

            verify(tournamentContactAccessService).checkPost(
                    eq(com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT), eq(T_SCOPE_ID), eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("presign: canPost 無し（例外）は presign できない（権限昇格防止）")
        void presign非投稿権限者は不可() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));
            doThrow(new BusinessException(
                    com.mannschaft.app.tournament.TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .when(tournamentContactAccessService).checkPost(any(), any(), any());

            assertThatThrownBy(() -> service.generateUploadUrl(presignReq(TargetType.THREAD, THREAD_ID), OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("他者添付削除: TOURNAMENT は canPost を要求し checkMembership/checkOwnerOrAdmin に落ちない")
        void delete他者はcanPost() {
            given(attachmentRepository.findById(ATTACHMENT_ID))
                    .willReturn(Optional.of(attachment(OTHER_USER_ID)));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));

            service.deleteAttachment(ATTACHMENT_ID, USER_ID);

            verify(tournamentContactAccessService).checkPost(
                    eq(com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT), eq(T_SCOPE_ID), eq(USER_ID));
            verify(accessGuard, never()).checkOwnerOrAdmin(any(), any(), any(), any());
        }
    }
}
