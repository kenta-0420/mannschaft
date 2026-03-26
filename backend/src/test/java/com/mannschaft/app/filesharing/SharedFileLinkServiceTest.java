package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import com.mannschaft.app.filesharing.service.SharedFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link SharedFileLinkService} の単体テスト。
 * 共有リンクの作成・アクセス・パスワード検証を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileLinkService 単体テスト")
class SharedFileLinkServiceTest {

    @Mock
    private SharedFileLinkRepository linkRepository;

    @Mock
    private SharedFileService fileService;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private SharedFileLinkService sharedFileLinkService;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("accessLink")
    class AccessLink {

        @Test
        @DisplayName("リンクアクセス_有効_ファイル情報返却")
        void リンクアクセス_有効_ファイル情報返却() {
            // Given
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("valid-token")
                    .expiresAt(LocalDateTime.now().plusDays(1)).createdBy(USER_ID).build();
            FileResponse fileResponse = new FileResponse(FILE_ID, 1L, "test.pdf", "key",
                    1024L, "application/pdf", null, USER_ID, 1, null, null);

            given(linkRepository.findByToken("valid-token")).willReturn(Optional.of(link));
            given(fileService.getFile(FILE_ID)).willReturn(fileResponse);

            // When
            FileResponse result = sharedFileLinkService.accessLink("valid-token", null);

            // Then
            assertThat(result.getName()).isEqualTo("test.pdf");
            assertThat(link.getAccessCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("リンクアクセス_期限切れ_BusinessException")
        void リンクアクセス_期限切れ_BusinessException() {
            // Given
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("expired-token")
                    .expiresAt(LocalDateTime.now().minusDays(1)).createdBy(USER_ID).build();

            given(linkRepository.findByToken("expired-token")).willReturn(Optional.of(link));

            // When & Then
            assertThatThrownBy(() -> sharedFileLinkService.accessLink("expired-token", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_EXPIRED));
        }

        @Test
        @DisplayName("リンクアクセス_パスワード不正_BusinessException")
        void リンクアクセス_パスワード不正_BusinessException() {
            // Given
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("pw-token")
                    .passwordHash("encoded_pw").createdBy(USER_ID).build();

            AccessLinkRequest request = new AccessLinkRequest("wrong");

            given(linkRepository.findByToken("pw-token")).willReturn(Optional.of(link));
            given(passwordEncoder.matches("wrong", "encoded_pw")).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> sharedFileLinkService.accessLink("pw-token", request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_PASSWORD_INVALID));
        }

        @Test
        @DisplayName("リンクアクセス_トークン不存在_BusinessException")
        void リンクアクセス_トークン不存在_BusinessException() {
            // Given
            given(linkRepository.findByToken("unknown")).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileLinkService.accessLink("unknown", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_NOT_FOUND));
        }
    }
}
