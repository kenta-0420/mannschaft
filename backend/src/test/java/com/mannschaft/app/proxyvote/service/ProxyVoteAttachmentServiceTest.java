package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteAttachmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.mannschaft.app.common.event.DomainEvent;

/**
 * {@link ProxyVoteAttachmentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteAttachmentService 単体テスト")
class ProxyVoteAttachmentServiceTest {

    @Mock private ProxyVoteSessionService sessionService;
    @Mock private ProxyVoteAttachmentRepository attachmentRepository;
    @Mock private ProxyVoteMapper mapper;
    @Mock private StorageService storageService;
    @Mock private DomainEventPublisher eventPublisher;

    @InjectMocks
    private ProxyVoteAttachmentService service;

    @Nested
    @DisplayName("addSessionAttachment")
    class AddSessionAttachment {

        @Test
        @DisplayName("異常系: ファイルがnullの場合エラー")
        void ファイルnull() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionService.findSessionOrThrow(1L)).willReturn(session);

            assertThatThrownBy(() -> service.addSessionAttachment(1L, null, "DOCUMENT", 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        @Test
        @DisplayName("異常系: ファイルサイズ超過")
        void ファイルサイズ超過() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionService.findSessionOrThrow(1L)).willReturn(session);

            MultipartFile file = mock(MultipartFile.class);
            given(file.isEmpty()).willReturn(false);
            given(file.getContentType()).willReturn("application/pdf");
            given(file.getSize()).willReturn(51L * 1024 * 1024); // 51MB

            assertThatThrownBy(() -> service.addSessionAttachment(1L, file, "DOCUMENT", 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.FILE_SIZE_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("addMotionAttachment")
    class AddMotionAttachment {

        @Test
        @DisplayName("異常系: 議案添付でMINUTESタイプは不可")
        void MINUTES不可() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder().sessionId(10L).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionService.findMotionOrThrow(1L)).willReturn(motion);
            given(sessionService.findSessionOrThrow(10L)).willReturn(session);

            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.addMotionAttachment(1L, file, "MINUTES", 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.MINUTES_SESSION_ONLY);
        }
    }

    @Nested
    @DisplayName("deleteAttachment")
    class DeleteAttachment {

        @Test
        @DisplayName("異常系: 添付ファイルが見つからない")
        void 添付不存在() {
            given(attachmentRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteAttachment(99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.ATTACHMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("正常系: 添付ファイル削除でS3削除イベント発火")
        void 削除成功() {
            ProxyVoteAttachmentEntity attachment = ProxyVoteAttachmentEntity.builder()
                    .fileKey("proxy-votes/session/1/abc").build();
            given(attachmentRepository.findById(1L)).willReturn(Optional.of(attachment));

            service.deleteAttachment(1L);

            verify(attachmentRepository).delete(attachment);
            verify(eventPublisher).publish(any(DomainEvent.class));
        }
    }
}
