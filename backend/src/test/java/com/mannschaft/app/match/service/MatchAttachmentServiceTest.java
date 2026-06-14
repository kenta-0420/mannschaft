package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchAttachmentService} の純 UT（test-first・01 §B.7 / 03 §C.7a）。
 *
 * <p>SVG 除外・サイズ上限・件数上限・IDOR 逆引き（match_id 帰属）・記録権限委譲を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchAttachmentService（局面写真添付）UT")
class MatchAttachmentServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchAttachmentRepository attachmentRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private StorageService storageService;

    @InjectMocks
    private MatchAttachmentService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.SHOGI)
                .stateModel(Sport.SHOGI.stateModel())
                .status(MatchStatus.IN_PROGRESS)
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(match);
        lenient().when(attachmentRepository.countByMatchId(matchId)).thenReturn(0L);
        lenient().when(attachmentRepository.save(any())).thenAnswer(inv -> {
            MatchAttachmentEntity a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
        lenient().when(storageService.generateUploadUrl(any(), any(), any()))
                .thenReturn(new PresignedUploadResult("https://upload", "match/x/key", 900));
    }

    // ─── presign: SVG 除外・サイズ上限・件数上限 ──────────────────

    @Test
    @DisplayName("presign: 画像（JPEG）は許可され server 採番 key で発行される")
    void presignImageOk() {
        MatchAttachmentService.PresignResult r =
                service.generateUploadUrl(matchId, ORG, ACTOR, "image/jpeg", 1024L);

        assertThat(r.getUploadUrl()).isEqualTo("https://upload");
        // fileKey はクライアント入力ではなく server 採番（match/{org}/{matchId}/... 形式）
        assertThat(r.getFileKey()).startsWith("match/" + ORG + "/" + matchId + "/");
        verify(matchAccessService).assertCanRecordTimeline(ACTOR, match);
    }

    @Test
    @DisplayName("presign: SVG は除外（MATCH_032）= XSS ベクタ")
    void presignSvgRejected() {
        assertThatThrownBy(() -> service.generateUploadUrl(matchId, ORG, ACTOR, "image/svg+xml", 1024L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_032);
        verify(storageService, never()).generateUploadUrl(any(), any(), any());
    }

    @Test
    @DisplayName("presign: 画像以外（PDF）は除外（MATCH_032）= 局面写真は画像のみ")
    void presignNonImageRejected() {
        assertThatThrownBy(() -> service.generateUploadUrl(matchId, ORG, ACTOR, "application/pdf", 1024L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_032);
    }

    @Test
    @DisplayName("presign: サイズ上限超過は 400（MATCH_033）")
    void presignSizeExceeded() {
        long over = MatchAttachmentService.MAX_FILE_SIZE_BYTES + 1;
        assertThatThrownBy(() -> service.generateUploadUrl(matchId, ORG, ACTOR, "image/png", over))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_033);
    }

    @Test
    @DisplayName("presign: 件数上限到達は 400（MATCH_034）")
    void presignCountExceeded() {
        when(attachmentRepository.countByMatchId(matchId))
                .thenReturn((long) MatchAttachmentService.MAX_ATTACHMENTS_PER_MATCH);
        assertThatThrownBy(() -> service.generateUploadUrl(matchId, ORG, ACTOR, "image/png", 1024L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_034);
    }

    // ─── IDOR 逆引き（match_id 帰属） ─────────────────────────────

    @Test
    @DisplayName("download-url: 別 match の添付 ID を指定すると 404（MATCH_031・IDOR）")
    void downloadUrlIdorRejected() {
        UUID attId = UUID.randomUUID();
        MatchAttachmentEntity foreign = MatchAttachmentEntity.builder()
                .matchId(UUID.randomUUID()) // 別 match に属する
                .fileKey("match/other/key")
                .contentType("image/png")
                .fileSize(10L)
                .createdBy(ACTOR)
                .build();
        foreign.setId(attId);
        when(attachmentRepository.findById(attId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.generateDownloadUrl(matchId, attId, ORG))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_031);
        verify(storageService, never()).generateDownloadUrl(any(), any());
    }

    @Test
    @DisplayName("download-url: 同一 match の添付なら短命 URL を発行する")
    void downloadUrlOk() {
        UUID attId = UUID.randomUUID();
        MatchAttachmentEntity att = MatchAttachmentEntity.builder()
                .matchId(matchId)
                .fileKey("match/" + ORG + "/" + matchId + "/k")
                .contentType("image/png")
                .fileSize(10L)
                .createdBy(ACTOR)
                .build();
        att.setId(attId);
        when(attachmentRepository.findById(attId)).thenReturn(Optional.of(att));
        when(storageService.generateDownloadUrl(any(), any())).thenReturn("https://dl");

        MatchAttachmentService.DownloadUrl dl = service.generateDownloadUrl(matchId, attId, ORG);
        assertThat(dl.getDownloadUrl()).isEqualTo("https://dl");
    }

    @Test
    @DisplayName("delete: 別 match の添付 ID 指定は 404（MATCH_031）・R2 削除しない")
    void deleteIdorRejected() {
        UUID attId = UUID.randomUUID();
        MatchAttachmentEntity foreign = MatchAttachmentEntity.builder()
                .matchId(UUID.randomUUID())
                .fileKey("match/other/key")
                .contentType("image/png")
                .fileSize(10L)
                .createdBy(ACTOR)
                .build();
        foreign.setId(attId);
        when(attachmentRepository.findById(attId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deleteAttachment(matchId, attId, ORG, ACTOR))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_031);
        verify(storageService, never()).delete(any());
    }

    // ─── 確定: 記録権限・再検証 ──────────────────────────────────

    @Test
    @DisplayName("confirm: 画像メタを登録し記録権限を委譲する")
    void confirmOk() {
        MatchAttachmentService.ConfirmCommand cmd = MatchAttachmentService.ConfirmCommand.builder()
                .fileKey("match/" + ORG + "/" + matchId + "/k")
                .originalFilename("kyokumen.png")
                .contentType("image/png")
                .fileSize(2048L)
                .build();

        MatchAttachmentEntity saved = service.confirmAttachment(matchId, ORG, ACTOR, cmd);

        assertThat(saved.getMatchId()).isEqualTo(matchId);
        assertThat(saved.getContentType()).isEqualTo("image/png");
        verify(matchAccessService).assertCanRecordTimeline(ACTOR, match);
    }

    @Test
    @DisplayName("confirm: SVG は確定時も除外（MATCH_032）")
    void confirmSvgRejected() {
        MatchAttachmentService.ConfirmCommand cmd = MatchAttachmentService.ConfirmCommand.builder()
                .fileKey("k").contentType("image/svg+xml").fileSize(10L).build();

        assertThatThrownBy(() -> service.confirmAttachment(matchId, ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_032);
        verify(attachmentRepository, never()).save(any());
    }
}
