package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoTagResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoTagService} 単体テスト（Phase 4）。
 *
 * <p>設計書 §7.1 に基づき以下を検証する:</p>
 * <ul>
 *   <li>createTag 正常系 — 名前・色を指定してタグ作成成功</li>
 *   <li>createTag 100件上限 — 100件まで成功、101件目でエラー</li>
 *   <li>updateTag — 名前・色の更新</li>
 *   <li>deleteTag — 論理削除</li>
 *   <li>addTagsToMemo — メモにタグを追加（1メモ10個上限）</li>
 *   <li>removeTagFromMemo — メモからタグを除去</li>
 *   <li>所有者不一致 404 — 他人のタグ/メモには 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoTagService 単体テスト")
class ActionMemoTagServiceTest {

    @Mock
    private ActionMemoTagRepository tagRepository;

    @Mock
    private ActionMemoTagLinkRepository tagLinkRepository;

    @Mock
    private ActionMemoRepository memoRepository;

    @InjectMocks
    private ActionMemoTagService tagService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;

    // ==================================================================
    // createTag
    // ==================================================================

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("正常系: 名前と色を指定してタグ作成")
        void success() {
            // given
            CreateActionMemoTagRequest request = new CreateActionMemoTagRequest("運動", "#FF6B6B");
            given(tagRepository.countByUserId(USER_ID)).willReturn(5L);

            ActionMemoTagEntity saved = ActionMemoTagEntity.builder()
                    .userId(USER_ID)
                    .name("運動")
                    .color("#FF6B6B")
                    .sortOrder(0)
                    .build();
            ReflectionTestUtils.setField(saved, "id", 1L);
            given(tagRepository.save(any(ActionMemoTagEntity.class))).willReturn(saved);

            // when
            ActionMemoTagResponse response = tagService.createTag(request, USER_ID);

            // then
            assertThat(response.getName()).isEqualTo("運動");
            assertThat(response.getColor()).isEqualTo("#FF6B6B");
            assertThat(response.isDeleted()).isFalse();
            verify(tagRepository).save(any(ActionMemoTagEntity.class));
        }

        @Test
        @DisplayName("100件上限超過: ACTION_MEMO_TAG_LIMIT_EXCEEDED")
        void limitExceeded() {
            // given
            CreateActionMemoTagRequest request = new CreateActionMemoTagRequest("超過", null);
            given(tagRepository.countByUserId(USER_ID)).willReturn(100L);

            // when/then
            assertThatThrownBy(() -> tagService.createTag(request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_LIMIT_EXCEEDED));

            verify(tagRepository, never()).save(any());
        }
    }

    // ==================================================================
    // updateTag
    // ==================================================================

    @Nested
    @DisplayName("updateTag")
    class UpdateTag {

        @Test
        @DisplayName("正常系: 名前と色を更新")
        void success() {
            // given
            ActionMemoTagEntity existing = ActionMemoTagEntity.builder()
                    .userId(USER_ID)
                    .name("旧名前")
                    .color("#000000")
                    .sortOrder(0)
                    .build();
            ReflectionTestUtils.setField(existing, "id", 1L);
            given(tagRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(existing));
            given(tagRepository.save(any(ActionMemoTagEntity.class))).willReturn(existing);

            UpdateActionMemoTagRequest request = new UpdateActionMemoTagRequest("新名前", "#FFFFFF");

            // when
            ActionMemoTagResponse response = tagService.updateTag(1L, request, USER_ID);

            // then
            assertThat(existing.getName()).isEqualTo("新名前");
            assertThat(existing.getColor()).isEqualTo("#FFFFFF");
        }

        @Test
        @DisplayName("所有者不一致: 404")
        void ownerMismatch() {
            // given
            given(tagRepository.findByIdAndUserId(1L, OTHER_USER_ID)).willReturn(Optional.empty());

            UpdateActionMemoTagRequest request = new UpdateActionMemoTagRequest("名前", null);

            // when/then
            assertThatThrownBy(() -> tagService.updateTag(1L, request, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        }
    }

    // ==================================================================
    // deleteTag
    // ==================================================================

    @Nested
    @DisplayName("deleteTag")
    class DeleteTag {

        @Test
        @DisplayName("正常系: 論理削除")
        void success() {
            // given
            ActionMemoTagEntity existing = ActionMemoTagEntity.builder()
                    .userId(USER_ID)
                    .name("削除対象")
                    .color(null)
                    .sortOrder(0)
                    .build();
            ReflectionTestUtils.setField(existing, "id", 1L);
            given(tagRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(existing));
            given(tagRepository.save(any(ActionMemoTagEntity.class))).willReturn(existing);

            // when
            tagService.deleteTag(1L, USER_ID);

            // then
            assertThat(existing.getDeletedAt()).isNotNull();
            verify(tagRepository).save(existing);
        }

        @Test
        @DisplayName("所有者不一致: 404")
        void ownerMismatch() {
            given(tagRepository.findByIdAndUserId(1L, OTHER_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> tagService.deleteTag(1L, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        }
    }

    // ==================================================================
    // addTagsToMemo
    // ==================================================================

    @Nested
    @DisplayName("addTagsToMemo")
    class AddTagsToMemo {

        @Test
        @DisplayName("正常系: メモにタグを追加")
        void success() {
            // given
            Long memoId = 10L;
            ActionMemoEntity memo = buildMemo(memoId, USER_ID);
            given(memoRepository.findByIdAndUserId(memoId, USER_ID)).willReturn(Optional.of(memo));

            ActionMemoTagEntity tag1 = buildTag(1L, USER_ID, "タグ1");
            ActionMemoTagEntity tag2 = buildTag(2L, USER_ID, "タグ2");
            given(tagRepository.findByIdInAndUserId(List.of(1L, 2L), USER_ID))
                    .willReturn(List.of(tag1, tag2));

            given(tagLinkRepository.countByMemoId(memoId)).willReturn(0L);
            given(tagLinkRepository.existsByMemoIdAndTagId(memoId, 1L)).willReturn(false);
            given(tagLinkRepository.existsByMemoIdAndTagId(memoId, 2L)).willReturn(false);
            given(tagLinkRepository.save(any(ActionMemoTagLinkEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            tagService.addTagsToMemo(memoId, List.of(1L, 2L), USER_ID);

            // then
            ArgumentCaptor<ActionMemoTagLinkEntity> captor =
                    ArgumentCaptor.forClass(ActionMemoTagLinkEntity.class);
            verify(tagLinkRepository, org.mockito.Mockito.times(2)).save(captor.capture());
            List<ActionMemoTagLinkEntity> saved = captor.getAllValues();
            assertThat(saved).hasSize(2);
        }

        @Test
        @DisplayName("1メモ10個上限超過: ACTION_MEMO_TAG_PER_MEMO_LIMIT_EXCEEDED")
        void perMemoLimitExceeded() {
            // given
            Long memoId = 10L;
            ActionMemoEntity memo = buildMemo(memoId, USER_ID);
            given(memoRepository.findByIdAndUserId(memoId, USER_ID)).willReturn(Optional.of(memo));

            ActionMemoTagEntity tag = buildTag(1L, USER_ID, "タグ");
            given(tagRepository.findByIdInAndUserId(List.of(1L), USER_ID))
                    .willReturn(List.of(tag));

            // 既に10個付いている
            given(tagLinkRepository.countByMemoId(memoId)).willReturn(10L);
            given(tagLinkRepository.existsByMemoIdAndTagId(memoId, 1L)).willReturn(false);

            // when/then
            assertThatThrownBy(() -> tagService.addTagsToMemo(memoId, List.of(1L), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_PER_MEMO_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("メモ所有者不一致: 404")
        void memoOwnerMismatch() {
            given(memoRepository.findByIdAndUserId(10L, OTHER_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> tagService.addTagsToMemo(10L, List.of(1L), OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));
        }

        @Test
        @DisplayName("タグ所有者不一致: 404")
        void tagOwnerMismatch() {
            Long memoId = 10L;
            ActionMemoEntity memo = buildMemo(memoId, USER_ID);
            given(memoRepository.findByIdAndUserId(memoId, USER_ID)).willReturn(Optional.of(memo));

            // タグ1つだけ送信、だがリポジトリは0件返す（所有者不一致）
            given(tagRepository.findByIdInAndUserId(List.of(1L), USER_ID))
                    .willReturn(List.of());

            assertThatThrownBy(() -> tagService.addTagsToMemo(memoId, List.of(1L), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        }
    }

    // ==================================================================
    // removeTagFromMemo
    // ==================================================================

    @Nested
    @DisplayName("removeTagFromMemo")
    class RemoveTagFromMemo {

        @Test
        @DisplayName("正常系: メモからタグを除去")
        void success() {
            Long memoId = 10L;
            Long tagId = 1L;
            ActionMemoEntity memo = buildMemo(memoId, USER_ID);
            given(memoRepository.findByIdAndUserId(memoId, USER_ID)).willReturn(Optional.of(memo));

            ActionMemoTagLinkEntity link = ActionMemoTagLinkEntity.builder()
                    .memoId(memoId)
                    .tagId(tagId)
                    .build();
            given(tagLinkRepository.findByMemoIdAndTagId(memoId, tagId)).willReturn(Optional.of(link));

            // when
            tagService.removeTagFromMemo(memoId, tagId, USER_ID);

            // then
            verify(tagLinkRepository).delete(link);
        }

        @Test
        @DisplayName("紐付けが存在しない: 404")
        void linkNotFound() {
            Long memoId = 10L;
            Long tagId = 1L;
            ActionMemoEntity memo = buildMemo(memoId, USER_ID);
            given(memoRepository.findByIdAndUserId(memoId, USER_ID)).willReturn(Optional.of(memo));
            given(tagLinkRepository.findByMemoIdAndTagId(memoId, tagId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> tagService.removeTagFromMemo(memoId, tagId, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        }
    }

    // ==================================================================
    // getTags / getTag
    // ==================================================================

    @Nested
    @DisplayName("getTags / getTag")
    class GetTags {

        @Test
        @DisplayName("getTags: ユーザーのタグ一覧を取得")
        void listTags() {
            ActionMemoTagEntity tag = buildTag(1L, USER_ID, "運動");
            given(tagRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of(tag));

            List<ActionMemoTagResponse> result = tagService.getTags(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("運動");
        }

        @Test
        @DisplayName("getTag 所有者不一致: 404")
        void getTag_ownerMismatch() {
            given(tagRepository.findByIdAndUserId(1L, OTHER_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> tagService.getTag(1L, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        }
    }

    // ==================================================================
    // ヘルパー
    // ==================================================================

    private ActionMemoEntity buildMemo(Long id, Long userId) {
        ActionMemoEntity memo = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(LocalDate.of(2026, 4, 9))
                .content("テストメモ")
                .build();
        ReflectionTestUtils.setField(memo, "id", id);
        return memo;
    }

    private ActionMemoTagEntity buildTag(Long id, Long userId, String name) {
        ActionMemoTagEntity tag = ActionMemoTagEntity.builder()
                .userId(userId)
                .name(name)
                .color(null)
                .sortOrder(0)
                .build();
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }
}
