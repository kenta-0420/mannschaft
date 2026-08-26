package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentPresignRequest;
import com.mannschaft.app.bulletin.dto.AttachmentPresignResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CreateAttachmentRequest;
import com.mannschaft.app.bulletin.service.BulletinAttachmentService;
import com.mannschaft.app.common.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinAttachmentController} の単体テスト。
 *
 * <p>SecurityContextHolder にユーザー ID を設定し、コントローラーを直接呼び出して
 * Service への委譲・HTTP ステータス・レスポンス包装を検証する（既存 bulletin コントローラー
 * テストと同流儀）。認可ロジック自体は {@code BulletinAttachmentServiceTest} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinAttachmentController 単体テスト")
class BulletinAttachmentControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long ATTACHMENT_ID = 300L;

    @Mock
    private BulletinAttachmentService bulletinAttachmentService;

    @InjectMocks
    private BulletinAttachmentController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("presign は 200 で uploadUrl/fileKey を返す")
    void presign() {
        AttachmentPresignRequest req =
                new AttachmentPresignRequest(TargetType.THREAD, THREAD_ID, "d.pdf", "application/pdf", 1024L);
        given(bulletinAttachmentService.generateUploadUrl(req, USER_ID))
                .willReturn(new AttachmentPresignResponse("https://r2/put", "bulletin/k", 900L));

        ResponseEntity<ApiResponse<AttachmentPresignResponse>> res = controller.presignUpload(req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().fileKey()).isEqualTo("bulletin/k");
        verify(bulletinAttachmentService).generateUploadUrl(req, USER_ID);
    }

    @Test
    @DisplayName("確定は 201 を返す")
    void confirm() {
        CreateAttachmentRequest req = new CreateAttachmentRequest(
                TargetType.THREAD, THREAD_ID, "bulletin/k", "d.pdf", 1024L, "application/pdf");
        given(bulletinAttachmentService.confirmAttachment(req, USER_ID)).willReturn(
                new AttachmentResponse(ATTACHMENT_ID, "THREAD", THREAD_ID, "bulletin/k", "d.pdf", 1024L,
                        "application/pdf", USER_ID, null));

        ResponseEntity<ApiResponse<AttachmentResponse>> res = controller.confirmAttachment(req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().getData().getId()).isEqualTo(ATTACHMENT_ID);
    }

    @Test
    @DisplayName("スレッド添付一覧を返す")
    void listThread() {
        given(bulletinAttachmentService.listThreadAttachments(THREAD_ID, USER_ID)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<AttachmentResponse>>> res = controller.listThreadAttachments(THREAD_ID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bulletinAttachmentService).listThreadAttachments(THREAD_ID, USER_ID);
    }

    @Test
    @DisplayName("返信添付一覧を返す")
    void listReply() {
        given(bulletinAttachmentService.listReplyAttachments(REPLY_ID, USER_ID)).willReturn(List.of());

        controller.listReplyAttachments(REPLY_ID);

        verify(bulletinAttachmentService).listReplyAttachments(REPLY_ID, USER_ID);
    }

    @Test
    @DisplayName("download-url は 200 で短命 URL を返す")
    void downloadUrl() {
        given(bulletinAttachmentService.generateDownloadUrl(ATTACHMENT_ID, USER_ID))
                .willReturn(new AttachmentDownloadUrlResponse("https://r2/get", 300L));

        ResponseEntity<ApiResponse<AttachmentDownloadUrlResponse>> res = controller.downloadUrl(ATTACHMENT_ID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().downloadUrl()).isEqualTo("https://r2/get");
    }

    @Test
    @DisplayName("削除は 204 を返し Service に委譲する")
    void delete() {
        ResponseEntity<Void> res = controller.deleteAttachment(ATTACHMENT_ID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(bulletinAttachmentService).deleteAttachment(eq(ATTACHMENT_ID), eq(USER_ID));
    }
}
