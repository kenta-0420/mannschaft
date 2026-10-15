package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.dto.FormUploadUrlRequest;
import com.mannschaft.app.forms.dto.UpdateFormSubmissionRequest;
import com.mannschaft.app.forms.service.FormSubmissionService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * F05.7 Phase 11 第一陣 A 分類: フォーム提出実行 API のコントローラー単体テスト。
 *
 * <p>認可根治戦役 Wave6 ロットD: FormSubmissionController#updateSubmission / #presignUpload /
 * #deleteSubmission の所有者検証委譲（AuthorizedInService）を追加で固定する。いずれも
 * {@code FormSubmissionService} が {@code findByIdAndSubmittedBy(submissionId,
 * SecurityUtils.getCurrentUserId())} で所有者一致を検証してから操作する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormSubmissionController 単体テスト (Phase 11 submit)")
class FormSubmissionControllerTest {

    @Mock
    private FormSubmissionService submissionService;

    @InjectMocks
    private FormSubmissionController submissionController;

    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "team";
    private static final Long SCOPE_ID = 1L;
    private static final Long SUBMISSION_ID = 200L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST submit 正常系: 200 + Service の結果を ApiResponse でラップ")
    void submit_200() {
        FormSubmissionResponse stub = FormSubmissionResponse.builder()
                .id(SUBMISSION_ID).status("SUBMITTED")
                .scope(new FormSubmissionResponse.FormScopeDto(SCOPE_TYPE, SCOPE_ID))
                .meta(new FormSubmissionResponse.FormSubmissionMetaDto(100L, USER_ID, null, 1, 0L))
                .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(null))
                .audit(new FormSubmissionResponse.FormSubmissionAuditDto(null, null))
                .values(List.of())
                .build();
        given(submissionService.submit(SUBMISSION_ID, USER_ID)).willReturn(stub);

        ResponseEntity<ApiResponse<FormSubmissionResponse>> response =
                submissionController.submit(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(stub);
    }

    @Test
    @DisplayName("POST submit 異常系: 提出未存在で FORM_002")
    void submit_notFound() {
        willThrow(new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND))
                .given(submissionService).submit(SUBMISSION_ID, USER_ID);

        assertThatThrownBy(() -> submissionController.submit(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FormErrorCode.SUBMISSION_NOT_FOUND));
    }

    @Test
    @DisplayName("POST submit 異常系: 二重 submit で FORM_005")
    void submit_invalidStatus() {
        willThrow(new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS))
                .given(submissionService).submit(SUBMISSION_ID, USER_ID);

        assertThatThrownBy(() -> submissionController.submit(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FormErrorCode.INVALID_SUBMISSION_STATUS));
    }

    @Test
    @DisplayName("updateSubmission は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void updateSubmission_ownershipCheckedAgainstCurrentUser() {
        UpdateFormSubmissionRequest request = new UpdateFormSubmissionRequest(false, null);

        submissionController.updateSubmission(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID, request);

        verify(submissionService).updateSubmission(SUBMISSION_ID, USER_ID, request);
    }

    @Test
    @DisplayName("updateSubmission 異常系: 他人の提出は FORM_002（所有者不一致は404で存在秘匿）")
    void updateSubmission_notOwner_notFound() {
        UpdateFormSubmissionRequest request = new UpdateFormSubmissionRequest(false, null);
        willThrow(new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND))
                .given(submissionService).updateSubmission(SUBMISSION_ID, USER_ID, request);

        assertThatThrownBy(() ->
                submissionController.updateSubmission(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FormErrorCode.SUBMISSION_NOT_FOUND));
    }

    @Test
    @DisplayName("presignUpload は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void presignUpload_ownershipCheckedAgainstCurrentUser() {
        FormUploadUrlRequest request = new FormUploadUrlRequest();
        request.setFieldKey("attachment");
        request.setFileName("a.pdf");
        request.setContentType("application/pdf");
        request.setFileSize(1024L);

        submissionController.presignUpload(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID, request);

        verify(submissionService).presignUploadUrl(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID, USER_ID, request);
    }

    @Test
    @DisplayName("deleteSubmission は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void deleteSubmission_ownershipCheckedAgainstCurrentUser() {
        submissionController.deleteSubmission(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID);

        verify(submissionService).deleteSubmission(SUBMISSION_ID, USER_ID);
    }

    @Test
    @DisplayName("deleteSubmission 異常系: 他人の提出は FORM_002（所有者不一致は404で存在秘匿）")
    void deleteSubmission_notOwner_notFound() {
        willThrow(new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND))
                .given(submissionService).deleteSubmission(SUBMISSION_ID, USER_ID);

        assertThatThrownBy(() ->
                submissionController.deleteSubmission(SCOPE_TYPE, SCOPE_ID, SUBMISSION_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FormErrorCode.SUBMISSION_NOT_FOUND));
    }
}
