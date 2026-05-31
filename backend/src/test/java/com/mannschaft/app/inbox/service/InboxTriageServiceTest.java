package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxTriageService} 単体テスト（Mockito・Repository モック）。
 *
 * <p>設計書 02_api_design.md §3.3 / 03_business_logic.md §5 / 01_data_model.md §2.1 から、
 * snooze/unsnooze/archive/unarchive の upsert・遅延物理削除・過去時刻拒否を受け入れ条件化する。</p>
 *
 * <p><b>test-first（red 想定）</b>: 本体は三陣で実装する。現段階は
 * {@link UnsupportedOperationException} で失敗するのが正しい。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxTriageService 単体テスト")
class InboxTriageServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SOURCE_ID = 123L;
    private static final InboxSourceType SOURCE_TYPE = InboxSourceType.NOTIFICATION;

    @Mock
    private InboxItemStateRepository itemStateRepository;

    @InjectMocks
    private InboxTriageService triageService;

    /** 既存オーバーレイ行を生成するヘルパー。 */
    private InboxItemStateEntity existing(LocalDateTime snoozedUntil, LocalDateTime archivedAt) {
        InboxItemStateEntity e = new InboxItemStateEntity();
        e.setUserId(USER_ID);
        e.setSourceType(SOURCE_TYPE);
        e.setSourceId(SOURCE_ID);
        e.setSnoozedUntil(snoozedUntil);
        e.setArchivedAt(archivedAt);
        return e;
    }

    // ─────────────────────────────────────────────────────────────────
    // snooze
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("snooze")
    class Snooze {

        @Test
        @DisplayName("異常系: 過去時刻 → INBOX_INVALID_SNOOZE_TIME を投げ、保存しない")
        void pastTime_throwsInvalidSnoozeTime() {
            LocalDateTime past = LocalDateTime.now().minusHours(1);

            assertThatThrownBy(() -> triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, past))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_INVALID_SNOOZE_TIME);

            verify(itemStateRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 未来時刻・既存行なし → 新規 save（upsert insert）")
        void futureTime_noExisting_inserts() {
            LocalDateTime future = LocalDateTime.now().plusHours(3);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.empty());
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, future);

            verify(itemStateRepository).save(any(InboxItemStateEntity.class));
        }

        @Test
        @DisplayName("正常系: 未来時刻・既存行あり → 既存行を更新（upsert update）")
        void futureTime_existing_updates() {
            LocalDateTime future = LocalDateTime.now().plusHours(3);
            InboxItemStateEntity row = existing(null, null);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, future);

            verify(itemStateRepository).save(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // archive
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("archive")
    class Archive {

        @Test
        @DisplayName("正常系: 既存行なし → archived_at をセットして save（upsert）")
        void noExisting_upsertsArchivedAt() {
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.empty());
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.archive(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).save(any(InboxItemStateEntity.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // unsnooze（両カラム NULL → 物理削除）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unsnooze")
    class Unsnooze {

        @Test
        @DisplayName("正常系: スヌーズのみの行を解除 → 両カラム NULL になり物理削除（save しない）")
        void snoozeOnly_unsnooze_deletesRow() {
            InboxItemStateEntity row = existing(LocalDateTime.now().plusHours(3), null);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));

            triageService.unsnooze(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).delete(row);
            verify(itemStateRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: アーカイブも併存する行を unsnooze → archived_at が残るため削除しない（update）")
        void alsoArchived_unsnooze_keepsRow() {
            InboxItemStateEntity row = existing(LocalDateTime.now().plusHours(3), LocalDateTime.now());
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.unsnooze(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository, never()).delete(any());
            verify(itemStateRepository).save(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // unarchive（両カラム NULL → 物理削除）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unarchive")
    class Unarchive {

        @Test
        @DisplayName("正常系: アーカイブのみの行を解除 → 両カラム NULL になり物理削除（save しない）")
        void archiveOnly_unarchive_deletesRow() {
            InboxItemStateEntity row = existing(null, LocalDateTime.now());
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));

            triageService.unarchive(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).delete(row);
            verify(itemStateRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // IDOR: 本人に可視でない対象 → INBOX_SOURCE_NOT_FOUND
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IDOR（可視性検証）")
    class Idor {

        @Test
        @Disabled("三陣への申し送り: 可視性検証の協力オブジェクト（各アダプタの isVisibleTo もしくは "
                + "AggregationService 経由の可視性判定）が骨格 InboxTriageService に未注入のため保留。"
                + "三陣で可視性コラボレータを注入後、本テストを有効化し、可視でない (sourceType, sourceId) への "
                + "snooze/archive/label が INBOX_SOURCE_NOT_FOUND を投げ、オーバーレイ行を作らないことを検証する"
                + "（設計書 04_security_operations.md §1.2）。")
        @DisplayName("異常系: 本人に可視でない通知への snooze → INBOX_SOURCE_NOT_FOUND（保留）")
        void notVisible_snooze_throwsSourceNotFound() {
            // TODO(三陣): 可視性コラボレータをモックし、isVisibleTo=false を返させて
            //   triageService.snooze(...) が INBOX_SOURCE_NOT_FOUND を投げることを検証する。
            assertThatThrownBy(() -> triageService.snooze(
                    USER_ID, SOURCE_TYPE, 999L, LocalDateTime.now().plusHours(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_SOURCE_NOT_FOUND);
        }
    }
}
