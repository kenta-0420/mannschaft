package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxLabelService} 単体テスト（Mockito・Repository モック）。
 *
 * <p>設計書 02_api_design.md §3.4 / 04_security_operations.md §1・§2 から、
 * CRUD・上限（20/通知 10）・同名重複・色/アイコン形式・IDOR（他人ラベル）・付与の可視性検証・
 * 冪等・論理削除を受け入れ条件化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxLabelService 単体テスト")
class InboxLabelServiceTest {

    private static final Long USER_ID = 1L;
    private static final InboxSourceType SOURCE_TYPE = InboxSourceType.NOTIFICATION;
    private static final Long SOURCE_ID = 123L;

    @Mock
    private NotificationLabelRepository labelRepository;

    @Mock
    private InboxLabelLinkRepository labelLinkRepository;

    @Mock
    private InboxItemVisibilityChecker visibilityChecker;

    /**
     * 認可ゲートは実物を使う（判定対象のリポジトリ・可視性チェッカーは上のモックを流用する）。
     * ラベル所有判定は {@code labelRepository.findByIdAndUserId}、対象通知の可視性判定は
     * {@code visibilityChecker.isVisibleTo} のままなので、各テストのスタブはそのまま認可判定に効く。
     */
    private InboxLabelService service;

    @BeforeEach
    void wireService() {
        service = new InboxLabelService(labelRepository, labelLinkRepository,
                new InboxAccessGuard(labelRepository, visibilityChecker));
    }

    private NotificationLabelEntity label(UUID id, Long userId, String name) {
        NotificationLabelEntity e = new NotificationLabelEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setName(name);
        e.setSortOrder(0);
        return e;
    }

    // ─────────────────────────────────────────────────────────────────
    // getLabels
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getLabels")
    class GetLabels {

        @Test
        @DisplayName("正常系: 現役ラベルを sortOrder 昇順で DTO 化して返す")
        void returnsLabels() {
            NotificationLabelEntity e = label(UUID.randomUUID(), USER_ID, "経理");
            e.setColor("#f59e0b");
            e.setIcon("pi-wallet");
            e.setSortOrder(3);
            given(labelRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of(e));

            List<LabelDto> result = service.getLabels(USER_ID);

            assertThat(result).singleElement().satisfies(dto -> {
                assertThat(dto.name()).isEqualTo("経理");
                assertThat(dto.color()).isEqualTo("#f59e0b");
                assertThat(dto.icon()).isEqualTo("pi-wallet");
                assertThat(dto.sortOrder()).isEqualTo(3);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // createLabel
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createLabel")
    class CreateLabel {

        @Test
        @DisplayName("正常系: name をトリムして保存・DTO 返却")
        void creates() {
            given(labelRepository.countByUserId(USER_ID)).willReturn(0L);
            given(labelRepository.existsByUserIdAndName(USER_ID, "要返信")).willReturn(false);
            given(labelRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.createLabel(USER_ID, "  要返信  ", "#3b82f6", "pi-reply");

            ArgumentCaptor<NotificationLabelEntity> captor =
                    ArgumentCaptor.forClass(NotificationLabelEntity.class);
            verify(labelRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("要返信");
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("異常系: 上限 20 到達 → INBOX_LABEL_LIMIT_EXCEEDED・保存しない")
        void limitExceeded() {
            given(labelRepository.countByUserId(USER_ID)).willReturn(20L);

            assertThatThrownBy(() -> service.createLabel(USER_ID, "x", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_LIMIT_EXCEEDED);

            verify(labelRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 現役同名重複 → INBOX_LABEL_NAME_DUPLICATE")
        void duplicateName() {
            given(labelRepository.countByUserId(USER_ID)).willReturn(1L);
            given(labelRepository.existsByUserIdAndName(USER_ID, "経理")).willReturn(true);

            assertThatThrownBy(() -> service.createLabel(USER_ID, "経理", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NAME_DUPLICATE);

            verify(labelRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 不正な色形式 → COMMON_001")
        void invalidColor() {
            assertThatThrownBy(() -> service.createLabel(USER_ID, "x", "red", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("異常系: 不正なアイコンプレフィックス → COMMON_001")
        void invalidIcon() {
            assertThatThrownBy(() -> service.createLabel(USER_ID, "x", null, "fa-bell"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_001);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // updateLabel
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateLabel")
    class UpdateLabel {

        @Test
        @DisplayName("正常系: 名前/色/アイコン/順序を更新する")
        void updates() {
            UUID id = UUID.randomUUID();
            NotificationLabelEntity existing = label(id, USER_ID, "旧");
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.of(existing));
            given(labelRepository.existsByUserIdAndName(USER_ID, "新")).willReturn(false);
            given(labelRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            LabelDto dto = service.updateLabel(USER_ID, id, "新", "#123456", "pi-tag", 5);

            assertThat(dto.name()).isEqualTo("新");
            assertThat(dto.color()).isEqualTo("#123456");
            assertThat(dto.icon()).isEqualTo("pi-tag");
            assertThat(dto.sortOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("IDOR: 他人/不存在ラベル → INBOX_LABEL_NOT_FOUND")
        void notFound() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateLabel(USER_ID, id, "x", null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 別ラベルと同名へ改名 → INBOX_LABEL_NAME_DUPLICATE")
        void renameToDuplicate() {
            UUID id = UUID.randomUUID();
            NotificationLabelEntity existing = label(id, USER_ID, "旧");
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.of(existing));
            given(labelRepository.existsByUserIdAndName(USER_ID, "経理")).willReturn(true);

            assertThatThrownBy(() -> service.updateLabel(USER_ID, id, "経理", null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NAME_DUPLICATE);
        }

        @Test
        @DisplayName("正常系: 同名据え置き（名前を変えない）は重複検証を発火しない")
        void sameNameAllowed() {
            UUID id = UUID.randomUUID();
            NotificationLabelEntity existing = label(id, USER_ID, "経理");
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.of(existing));
            given(labelRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.updateLabel(USER_ID, id, "経理", null, null, null);

            verify(labelRepository, never()).existsByUserIdAndName(any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // deleteLabel
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteLabel")
    class DeleteLabel {

        @Test
        @DisplayName("正常系: softDelete を呼び保存する")
        void softDeletes() {
            UUID id = UUID.randomUUID();
            NotificationLabelEntity existing = label(id, USER_ID, "x");
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.of(existing));
            given(labelRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.deleteLabel(USER_ID, id);

            assertThat(existing.getDeletedAt()).isNotNull();
            verify(labelRepository).save(existing);
        }

        @Test
        @DisplayName("IDOR: 他人/不存在ラベル → INBOX_LABEL_NOT_FOUND")
        void notFound() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteLabel(USER_ID, id))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // assignLabel
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("assignLabel")
    class AssignLabel {

        private UUID setupOwnedLabel() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID))
                    .willReturn(Optional.of(label(id, USER_ID, "x")));
            return id;
        }

        @Test
        @DisplayName("正常系: 所有ラベル＋可視通知＋上限内 → リンクを insert")
        void assigns() {
            UUID id = setupOwnedLabel();
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(id, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(false);
            given(labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(0L);

            service.assignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID);

            verify(labelLinkRepository).save(any(InboxLabelLinkEntity.class));
        }

        @Test
        @DisplayName("IDOR: 他人ラベル → INBOX_LABEL_NOT_FOUND・可視性検証もリンク保存もしない")
        void notOwnedLabel() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NOT_FOUND);

            verify(labelLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("可視性: 本人に不可視な通知 → INBOX_SOURCE_NOT_FOUND・リンクを作らない")
        void notVisible() {
            UUID id = setupOwnedLabel();
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, 999L)).willReturn(false);

            assertThatThrownBy(() -> service.assignLabel(USER_ID, id, SOURCE_TYPE, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_SOURCE_NOT_FOUND);

            verify(labelLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("冪等: 既に付与済み → 何もしない（save しない）")
        void idempotent() {
            UUID id = setupOwnedLabel();
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(id, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(true);

            service.assignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID);

            verify(labelLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("上限: 1 通知 10 ラベル到達 → INBOX_LABEL_PER_ITEM_EXCEEDED")
        void perItemLimit() {
            UUID id = setupOwnedLabel();
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(id, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(false);
            given(labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(10L);

            assertThatThrownBy(() -> service.assignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_PER_ITEM_EXCEEDED);

            verify(labelLinkRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // unassignLabel
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unassignLabel")
    class UnassignLabel {

        @Test
        @DisplayName("正常系: リンクが存在 → delete する")
        void deletes() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID))
                    .willReturn(Optional.of(label(id, USER_ID, "x")));
            InboxLabelLinkEntity link = new InboxLabelLinkEntity();
            given(labelLinkRepository.findByLabelIdAndSourceTypeAndSourceId(id, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(link));

            service.unassignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID);

            verify(labelLinkRepository).delete(link);
        }

        @Test
        @DisplayName("冪等: リンクが無い → 何もしない（delete しない）")
        void idempotentNoLink() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID))
                    .willReturn(Optional.of(label(id, USER_ID, "x")));
            given(labelLinkRepository.findByLabelIdAndSourceTypeAndSourceId(id, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.empty());

            service.unassignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID);

            verify(labelLinkRepository, never()).delete(any());
        }

        @Test
        @DisplayName("IDOR: 他人ラベル → INBOX_LABEL_NOT_FOUND")
        void notOwnedLabel() {
            UUID id = UUID.randomUUID();
            given(labelRepository.findByIdAndUserId(id, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.unassignLabel(USER_ID, id, SOURCE_TYPE, SOURCE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // suggestApply（案C 1 タップ付与・find-or-create・冪等・上限）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("suggestApply")
    class SuggestApply {

        @Test
        @DisplayName("既存ラベル無し → createLabel で作成して付与する")
        void createsWhenAbsent() {
            UUID newId = UUID.randomUUID();
            given(labelRepository.findByUserIdAndName(USER_ID, "要返信")).willReturn(Optional.empty());
            given(labelRepository.countByUserId(USER_ID)).willReturn(0L);
            given(labelRepository.existsByUserIdAndName(USER_ID, "要返信")).willReturn(false);
            given(labelRepository.save(any())).willAnswer(inv -> {
                NotificationLabelEntity e = inv.getArgument(0);
                e.setId(newId);
                return e;
            });
            // 付与経路
            given(labelRepository.findByIdAndUserId(newId, USER_ID))
                    .willReturn(Optional.of(label(newId, USER_ID, "要返信")));
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(newId, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(false);
            given(labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(0L);

            LabelDto dto = service.suggestApply(USER_ID, "要返信", "#2563EB", SOURCE_TYPE, SOURCE_ID);

            assertThat(dto.name()).isEqualTo("要返信");
            // ラベル本体保存（作成）＋リンク保存（付与）の両方が起きる
            verify(labelRepository).save(any(NotificationLabelEntity.class));
            verify(labelLinkRepository).save(any(InboxLabelLinkEntity.class));
        }

        @Test
        @DisplayName("既存同名あり → 再利用して付与する（重複作成しない）")
        void reusesExisting() {
            UUID existingId = UUID.randomUUID();
            given(labelRepository.findByUserIdAndName(USER_ID, "要返信"))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            // 付与経路
            given(labelRepository.findByIdAndUserId(existingId, USER_ID))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(existingId, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(false);
            given(labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(0L);

            LabelDto dto = service.suggestApply(USER_ID, "要返信", "#2563EB", SOURCE_TYPE, SOURCE_ID);

            assertThat(dto.id()).isEqualTo(existingId);
            // ラベル本体は作成しない（再利用）。リンクのみ作成。
            verify(labelRepository, never()).save(any());
            verify(labelLinkRepository).save(any(InboxLabelLinkEntity.class));
        }

        @Test
        @DisplayName("冪等: 既存ラベルが既に付与済み → 作成も再付与もせず正常返却")
        void idempotentWhenAlreadyAssigned() {
            UUID existingId = UUID.randomUUID();
            given(labelRepository.findByUserIdAndName(USER_ID, "要返信"))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            given(labelRepository.findByIdAndUserId(existingId, USER_ID))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            // 既に付与済み
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(existingId, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(true);

            LabelDto dto = service.suggestApply(USER_ID, "要返信", "#2563EB", SOURCE_TYPE, SOURCE_ID);

            assertThat(dto.id()).isEqualTo(existingId);
            verify(labelRepository, never()).save(any());
            verify(labelLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("上限: 新規作成時に 20 件到達 → 既存 INBOX_LABEL_LIMIT_EXCEEDED（付与しない）")
        void limitExceededOnCreate() {
            given(labelRepository.findByUserIdAndName(USER_ID, "要返信")).willReturn(Optional.empty());
            given(labelRepository.countByUserId(USER_ID)).willReturn(20L);

            assertThatThrownBy(() ->
                    service.suggestApply(USER_ID, "要返信", "#2563EB", SOURCE_TYPE, SOURCE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_LIMIT_EXCEEDED);

            verify(labelLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("上限: 1 通知 10 ラベル到達 → 既存 INBOX_LABEL_PER_ITEM_EXCEEDED")
        void perItemLimitExceeded() {
            UUID existingId = UUID.randomUUID();
            given(labelRepository.findByUserIdAndName(USER_ID, "要返信"))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            given(labelRepository.findByIdAndUserId(existingId, USER_ID))
                    .willReturn(Optional.of(label(existingId, USER_ID, "要返信")));
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(existingId, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(false);
            given(labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(10L);

            assertThatThrownBy(() ->
                    service.suggestApply(USER_ID, "要返信", "#2563EB", SOURCE_TYPE, SOURCE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_LABEL_PER_ITEM_EXCEEDED);

            verify(labelLinkRepository, never()).save(any());
        }
    }
}
