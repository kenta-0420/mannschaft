package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F09.8.1 {@link MyCorkboardPinService} 単体テスト。
 *
 * <p>カバー範囲（設計書 §10.1）:</p>
 * <ul>
 *   <li>正常系: pin → isPinned=true + pinnedAt 設定</li>
 *   <li>正常系: unpin → isPinned=false + pinnedAt=null</li>
 *   <li>所有者でない場合 403 (CORKBOARD_011)</li>
 *   <li>TEAM スコープボード 403 (CORKBOARD_011)</li>
 *   <li>論理削除済みカード 400 (CORKBOARD_002)</li>
 *   <li>アーカイブ済みカードの pin 操作 400 (CORKBOARD_012)</li>
 *   <li>アーカイブ済みカードの unpin 操作は許可</li>
 *   <li>上限到達後の追加 pin 409 (CORKBOARD_013)</li>
 *   <li>既に pin されているカードへの pin 操作は上限カウント不要</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyCorkboardPinService 単体テスト")
class MyCorkboardPinServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long BOARD_ID = 10L;
    private static final Long CARD_ID = 100L;

    @Mock
    private CorkboardRepository boardRepository;

    @Mock
    private CorkboardCardRepository cardRepository;

    @Mock
    private CorkboardMapper corkboardMapper;

    @InjectMocks
    private MyCorkboardPinService service;

    /** ピン未設定・未アーカイブ・未削除の個人ボード所有カード。 */
    private CorkboardCardEntity activeCard;

    /** PERSONAL スコープのボード（USER_ID 所有）。 */
    private CorkboardEntity personalBoard;

    @BeforeEach
    void setUp() {
        personalBoard = CorkboardEntity.builder()
                .scopeType("PERSONAL")
                .ownerId(USER_ID)
                .name("仕事メモ")
                .build();
        activeCard = CorkboardCardEntity.builder()
                .corkboardId(BOARD_ID)
                .cardType("MEMO")
                .createdBy(USER_ID)
                .isArchived(false)
                .isPinned(false)
                .build();
    }

    /** mapper のスタブをまとめて設定する。引数 entity の状態をレスポンスに反映する。 */
    private void givenMapperEchoesEntity() {
        given(corkboardMapper.toCardResponse(any(CorkboardCardEntity.class)))
                .willAnswer(inv -> {
                    CorkboardCardEntity e = inv.getArgument(0);
                    return new CorkboardCardResponse(
                            CARD_ID, BOARD_ID, e.getCardType(), null, null, null,
                            null, null, null, null, null, null,
                            "NONE", "MEDIUM", 0, 0, 0, null, null,
                            e.getIsArchived(), e.getIsPinned(), e.getPinnedAt(),
                            USER_ID, null, null);
                });
    }

    @Nested
    @DisplayName("togglePin 正常系")
    class TogglePinSuccess {

        @Test
        @DisplayName("pin → isPinned=true + pinnedAt が設定される")
        void pin_正常_isPinned_と_pinnedAt_設定() {
            // Given
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(activeCard));
            given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(0);
            given(cardRepository.save(any(CorkboardCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            givenMapperEchoesEntity();

            // When
            CorkboardCardResponse result = service.togglePin(BOARD_ID, CARD_ID, true, USER_ID);

            // Then
            assertThat(result.getIsPinned()).isTrue();
            assertThat(result.getPinnedAt()).isNotNull();
            assertThat(activeCard.getIsPinned()).isTrue();
            assertThat(activeCard.getPinnedAt()).isNotNull();
            verify(cardRepository).save(activeCard);
        }

        @Test
        @DisplayName("unpin → isPinned=false + pinnedAt=null")
        void unpin_正常_isPinned_falseかつpinnedAt_null() {
            // Given: 既にピン済み
            CorkboardCardEntity pinnedCard = CorkboardCardEntity.builder()
                    .corkboardId(BOARD_ID)
                    .cardType("MEMO")
                    .createdBy(USER_ID)
                    .isArchived(false)
                    .isPinned(true)
                    .pinnedAt(LocalDateTime.now().minusHours(1))
                    .build();
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(pinnedCard));
            given(cardRepository.save(any(CorkboardCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            givenMapperEchoesEntity();

            // When
            CorkboardCardResponse result = service.togglePin(BOARD_ID, CARD_ID, false, USER_ID);

            // Then
            assertThat(result.getIsPinned()).isFalse();
            assertThat(result.getPinnedAt()).isNull();
            assertThat(pinnedCard.getIsPinned()).isFalse();
            assertThat(pinnedCard.getPinnedAt()).isNull();
            // unpin では上限カウントは不要
            verify(cardRepository, never()).countPinnedByOwnerIdAndScopePersonal(any());
        }

        @Test
        @DisplayName("既にピン済みカードへの pin 操作は上限カウントをスキップ（再 pin 冪等）")
        void 再pin_上限カウント不要() {
            // Given: 既にピン済みカード
            CorkboardCardEntity alreadyPinned = CorkboardCardEntity.builder()
                    .corkboardId(BOARD_ID)
                    .cardType("MEMO")
                    .createdBy(USER_ID)
                    .isArchived(false)
                    .isPinned(true)
                    .pinnedAt(LocalDateTime.now().minusDays(1))
                    .build();
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(alreadyPinned));
            given(cardRepository.save(any(CorkboardCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            givenMapperEchoesEntity();

            // When
            CorkboardCardResponse result = service.togglePin(BOARD_ID, CARD_ID, true, USER_ID);

            // Then
            assertThat(result.getIsPinned()).isTrue();
            // 既にピン済みなので countPinned は呼ばれない
            verify(cardRepository, never()).countPinnedByOwnerIdAndScopePersonal(any());
        }

        @Test
        @DisplayName("アーカイブ済みカードの unpin 操作は許可（権限喪失カードのクリーンアップ用）")
        void unpin_アーカイブ済み_許可() {
            // Given: アーカイブ済み + ピン済み
            CorkboardCardEntity archivedPinned = CorkboardCardEntity.builder()
                    .corkboardId(BOARD_ID)
                    .cardType("MEMO")
                    .createdBy(USER_ID)
                    .isArchived(true)
                    .isPinned(true)
                    .pinnedAt(LocalDateTime.now().minusDays(2))
                    .build();
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(archivedPinned));
            given(cardRepository.save(any(CorkboardCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            givenMapperEchoesEntity();

            // When
            CorkboardCardResponse result = service.togglePin(BOARD_ID, CARD_ID, false, USER_ID);

            // Then
            assertThat(result.getIsPinned()).isFalse();
            assertThat(archivedPinned.getIsPinned()).isFalse();
        }
    }

    @Nested
    @DisplayName("togglePin 異常系")
    class TogglePinFailure {

        @Test
        @DisplayName("所有者でない場合 CORKBOARD_011 (403相当) を投げる")
        void 所有者でない_011例外() {
            // Given: 他人のボードIDを USER_ID で検索 → 空 (所有者条件で除外される)
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_011"));
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("TEAM スコープのボード上のカードへの pin → CORKBOARD_011")
        void TEAMスコープ_011例外() {
            // Given: ownerId は一致するが scopeType=TEAM
            //   実装では「個人ボードは scopeType=PERSONAL かつ ownerId 必須」、
            //   findByIdAndOwnerId は ownerId のみで絞るため、誤って TEAM ボードが取れたケースを再現
            CorkboardEntity teamBoard = CorkboardEntity.builder()
                    .scopeType("TEAM")
                    .ownerId(USER_ID)
                    .name("チームボード")
                    .build();
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(teamBoard));

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_011"));
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("論理削除済みカード（findByIdAndCorkboardId が空）→ CORKBOARD_002")
        void 論理削除済みカード_002例外() {
            // Given
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_002"));
        }

        @Test
        @DisplayName("アーカイブ済みカードへの pin → CORKBOARD_012")
        void アーカイブ済み_pin_012例外() {
            // Given
            CorkboardCardEntity archivedCard = CorkboardCardEntity.builder()
                    .corkboardId(BOARD_ID)
                    .cardType("MEMO")
                    .createdBy(USER_ID)
                    .isArchived(true)
                    .isPinned(false)
                    .build();
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(archivedCard));

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_012"));
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("上限到達後の追加 pin → CORKBOARD_013")
        void 上限到達_013例外() {
            // Given: 50 件既にピン中
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.of(personalBoard));
            given(cardRepository.findByIdAndCorkboardId(CARD_ID, BOARD_ID))
                    .willReturn(Optional.of(activeCard));
            given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID))
                    .willReturn(MyCorkboardPinService.MAX_PINNED_PER_USER);

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_013"));
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("他ユーザーのボード ID 直叩き → 所有者検索で空 → CORKBOARD_011 (IDOR防止)")
        void 他人boardId_IDOR防止_011() {
            // Given: USER_ID で検索したが、boardId は OTHER_USER_ID 所有 → 空
            given(boardRepository.findByIdAndOwnerId(BOARD_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.togglePin(BOARD_ID, CARD_ID, true, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
