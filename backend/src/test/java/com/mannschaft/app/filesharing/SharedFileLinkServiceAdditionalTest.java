package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFileLinkService} の追加単体テスト。listLinks, createLink, deleteLink をカバーする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileLinkService 追加単体テスト")
class SharedFileLinkServiceAdditionalTest {

    @Mock
    private SharedFileLinkRepository linkRepository;

    @Mock
    private SharedFileService fileService;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private SharedFileLinkService service;

    private static final Long FILE_ID = 100L;
    private static final Long LINK_ID = 200L;
    private static final Long USER_ID = 10L;

    private LinkResponse mockLinkResponse(boolean hasPassword) {
        // LinkResponse(id, fileId, token, expiresAt, hasPassword, accessCount, lastAccessedAt, createdBy, createdAt)
        return new LinkResponse(LINK_ID, FILE_ID, "tok123", null, hasPassword, 0, null, USER_ID, null);
    }

    // ========================================
    // listLinks
    // ========================================

    @Nested
    @DisplayName("listLinks")
    class ListLinks {

        @Test
        @DisplayName("正常系: ファイルの共有リンク一覧が返却される")
        void リンク一覧_正常() {
            SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok123").accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findByFileIdOrderByCreatedAtDesc(FILE_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toLinkResponseList(any()))
                    .willReturn(List.of(mockLinkResponse(false)));

            List<LinkResponse> result = service.listLinks(FILE_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createLink - without password
    // ========================================

    @Nested
    @DisplayName("createLink")
    class CreateLink {

        @Test
        @DisplayName("正常系: パスワードなしリンクが作成される")
        void リンク作成_パスワードなし_正常() {
            CreateLinkRequest request = new CreateLinkRequest(
                    LocalDateTime.now().plusDays(7), null);
            SharedFileLinkEntity saved = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("gen-token").accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.save(any())).willReturn(saved);
            given(fileSharingMapper.toLinkResponse(saved)).willReturn(mockLinkResponse(false));

            LinkResponse result = service.createLink(FILE_ID, USER_ID, request);

            assertThat(result).isNotNull();
            assertThat(result.isHasPassword()).isFalse();
        }

        @Test
        @DisplayName("正常系: パスワードありリンクが作成される")
        void リンク作成_パスワードあり_正常() {
            CreateLinkRequest request = new CreateLinkRequest(
                    LocalDateTime.now().plusDays(7), "secret");
            given(passwordEncoder.encode("secret")).willReturn("$2a$hashed");
            SharedFileLinkEntity saved = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("gen-token").passwordHash("$2a$hashed")
                    .accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.save(any())).willReturn(saved);
            given(fileSharingMapper.toLinkResponse(saved)).willReturn(mockLinkResponse(true));

            LinkResponse result = service.createLink(FILE_ID, USER_ID, request);

            assertThat(result.isHasPassword()).isTrue();
            verify(passwordEncoder).encode("secret");
        }

        @Test
        @DisplayName("正常系: 空白パスワードはハッシュ化されない")
        void リンク作成_空白パスワード_ハッシュなし() {
            CreateLinkRequest request = new CreateLinkRequest(null, "   ");
            SharedFileLinkEntity saved = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("gen-token").accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.save(any())).willReturn(saved);
            given(fileSharingMapper.toLinkResponse(saved)).willReturn(mockLinkResponse(false));

            LinkResponse result = service.createLink(FILE_ID, USER_ID, request);

            assertThat(result.isHasPassword()).isFalse();
        }
    }

    // ========================================
    // deleteLink
    // ========================================

    @Nested
    @DisplayName("deleteLink")
    class DeleteLink {

        @Test
        @DisplayName("正常系: リンクが削除される")
        void リンク削除_正常() {
            SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok123").accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findById(LINK_ID)).willReturn(Optional.of(entity));

            service.deleteLink(LINK_ID);

            verify(linkRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: リンク不在でLINK_NOT_FOUND例外")
        void リンク削除_不在_例外() {
            given(linkRepository.findById(LINK_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteLink(LINK_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_NOT_FOUND));
        }
    }

    // ========================================
    // accessLink - password provided correctly
    // ========================================

    @Nested
    @DisplayName("accessLink - パスワード正常")
    class AccessLinkPasswordCorrect {

        @Test
        @DisplayName("正常系: パスワード正しくアクセスできる")
        void パスワード正しい_アクセス成功() {
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("pw-token").passwordHash("$2a$hashed")
                    .accessCount(0).createdBy(USER_ID).build();
            com.mannschaft.app.filesharing.dto.FileResponse fileResponse =
                    new com.mannschaft.app.filesharing.dto.FileResponse(
                            FILE_ID, 1L, "secure.pdf", "key", 2048L, "application/pdf",
                            null, USER_ID, 1, null, null);
            given(linkRepository.findByToken("pw-token")).willReturn(Optional.of(link));
            given(passwordEncoder.matches("correct", "$2a$hashed")).willReturn(true);
            given(fileService.getFile(FILE_ID)).willReturn(fileResponse);

            com.mannschaft.app.filesharing.dto.FileResponse result =
                    service.accessLink("pw-token",
                            new com.mannschaft.app.filesharing.dto.AccessLinkRequest("correct"));

            assertThat(result.getName()).isEqualTo("secure.pdf");
        }
    }
}
