package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.BulkInboxRequest;
import com.mannschaft.app.inbox.dto.BulkResultResponse;
import com.mannschaft.app.inbox.dto.TriageTargetRequest;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxBulkService} 単体テスト（Mockito）。
 *
 * <p>設計書 02_api_design.md §3.5 から、action 別の委譲・件数集計・部分失敗の skip 計上・
 * action 必須パラメータ欠落の全体 400 を受け入れ条件化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxBulkService 単体テスト")
class InboxBulkServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private InboxTriageService triageService;

    @Mock
    private InboxLabelService labelService;

    @Mock
    private InboxAccessGuard inboxAccessGuard;

    @InjectMocks
    private InboxBulkService service;

    private TriageTargetRequest target(InboxSourceType type, Long id) {
        return new TriageTargetRequest(type, id);
    }

    // ─────────────────────────────────────────────────────────────────
    // ARCHIVE
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ARCHIVE")
    class Archive {

        @Test
        @DisplayName("正常系: 全件成功 → processed=件数・skipped=0、各 item を triage に委譲")
        void allProcessed() {
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.ARCHIVE,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L),
                            target(InboxSourceType.MENTION, 9L)),
                    null, null);

            BulkResultResponse res = service.bulk(USER_ID, req);

            assertThat(res.getProcessed()).isEqualTo(2);
            assertThat(res.getSkipped()).isEqualTo(0);
            verify(triageService).archive(USER_ID, InboxSourceType.NOTIFICATION, 1L);
            verify(triageService).archive(USER_ID, InboxSourceType.MENTION, 9L);
        }

        @Test
        @DisplayName("部分失敗: 1 件が INBOX_SOURCE_NOT_FOUND → skipped=1・残りは processed")
        void partialFailureSkips() {
            doThrow(new BusinessException(InboxErrorCode.INBOX_SOURCE_NOT_FOUND))
                    .when(triageService).archive(USER_ID, InboxSourceType.NOTIFICATION, 1L);

            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.ARCHIVE,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L),
                            target(InboxSourceType.MENTION, 9L)),
                    null, null);

            BulkResultResponse res = service.bulk(USER_ID, req);

            assertThat(res.getProcessed()).isEqualTo(1);
            assertThat(res.getSkipped()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // SNOOZE
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SNOOZE")
    class Snooze {

        @Test
        @DisplayName("正常系: snoozedUntil 同梱で各 item を snooze に委譲")
        void delegatesSnooze() {
            OffsetDateTime until = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3);
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.SNOOZE,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L)),
                    until, null);

            service.bulk(USER_ID, req);

            verify(triageService).snooze(USER_ID, InboxSourceType.NOTIFICATION, 1L, until);
        }

        @Test
        @DisplayName("異常系: snoozedUntil 欠落 → COMMON_001（全体 400・委譲しない）")
        void missingSnoozedUntil() {
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.SNOOZE,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L)),
                    null, null);

            assertThatThrownBy(() -> service.bulk(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_001);

            verify(triageService, times(0)).snooze(any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LABEL_ADD
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LABEL_ADD")
    class LabelAdd {

        @Test
        @DisplayName("正常系: labelId 同梱で各 item を assignLabel に委譲")
        void delegatesAssign() {
            UUID labelId = UUID.randomUUID();
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.LABEL_ADD,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L)),
                    null, labelId);

            service.bulk(USER_ID, req);

            verify(labelService).assignLabel(USER_ID, labelId, InboxSourceType.NOTIFICATION, 1L);
        }

        @Test
        @DisplayName("異常系: labelId 欠落 → COMMON_001（全体 400）")
        void missingLabelId() {
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.LABEL_ADD,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L)),
                    null, null);

            assertThatThrownBy(() -> service.bulk(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("認可: 他者所有・不存在のラベルIDは item ループ前に 404 で全体を止める（付与も委譲しない）")
        void foreignLabelIdRejectedBeforeLoop() {
            UUID foreignLabelId = UUID.randomUUID();
            doThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_NOT_FOUND))
                    .when(inboxAccessGuard).requireOwnedLabel(USER_ID, foreignLabelId);

            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.LABEL_ADD,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L),
                            target(InboxSourceType.MENTION, 9L)),
                    null, foreignLabelId);

            assertThatThrownBy(() -> service.bulk(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NOT_FOUND);

            verify(labelService, times(0)).assignLabel(any(), any(), any(), any());
        }

        @Test
        @DisplayName("認可: 本人所有ラベルの検証は item ループ前に 1 回だけ行う")
        void ownedLabelVerifiedOnce() {
            UUID labelId = UUID.randomUUID();
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.LABEL_ADD,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L),
                            target(InboxSourceType.MENTION, 9L)),
                    null, labelId);

            service.bulk(USER_ID, req);

            verify(inboxAccessGuard, times(1)).requireOwnedLabel(USER_ID, labelId);
        }

        @Test
        @DisplayName("部分失敗: 上限超過 1 件 → skipped=1")
        void perItemLimitSkips() {
            UUID labelId = UUID.randomUUID();
            doThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_PER_ITEM_EXCEEDED))
                    .when(labelService).assignLabel(eq(USER_ID), eq(labelId),
                            eq(InboxSourceType.NOTIFICATION), eq(1L));

            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.LABEL_ADD,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L),
                            target(InboxSourceType.MENTION, 9L)),
                    null, labelId);

            BulkResultResponse res = service.bulk(USER_ID, req);

            assertThat(res.getProcessed()).isEqualTo(1);
            assertThat(res.getSkipped()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // UNARCHIVE
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNARCHIVE")
    class Unarchive {

        @Test
        @DisplayName("正常系: 各 item を unarchive に委譲")
        void delegatesUnarchive() {
            BulkInboxRequest req = new BulkInboxRequest(
                    BulkInboxRequest.BulkAction.UNARCHIVE,
                    List.of(target(InboxSourceType.NOTIFICATION, 1L)),
                    null, null);

            service.bulk(USER_ID, req);

            verify(triageService).unarchive(USER_ID, InboxSourceType.NOTIFICATION, 1L);
        }
    }
}
