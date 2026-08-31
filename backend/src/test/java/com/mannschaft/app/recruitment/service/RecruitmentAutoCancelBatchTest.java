package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentAutoCancelBatch} の単体テスト。
 * F03.11 Phase 3 §5.4 自動キャンセルバッチの主要パスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentAutoCancelBatch 単体テスト")
class RecruitmentAutoCancelBatchTest {

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentParticipantRepository participantRepository;

    @Mock
    private RecruitmentParticipantHistoryRepository historyRepository;

    @Mock
    private ConfirmableNotificationService confirmableNotificationService;

    @InjectMocks
    private RecruitmentAutoCancelBatch batch;

    private static final Long LISTING_ID = 100L;

    // ========================================
    // run - バッチメインループ
    // ========================================

    @Nested
    @DisplayName("run - 自動キャンセルバッチ実行")
    class Run {

        @Test
        @DisplayName("findAutoCancelTargets が空リスト → 処理なし")
        void run_noCandidates_doesNothing() {
            // given
            given(listingRepository.findAutoCancelTargets(any())).willReturn(Collections.emptyList());

            // when
            batch.run();

            // then: listingRepository.findByIdForUpdate は一切呼ばれない
            verify(listingRepository, never()).findByIdForUpdate(anyLong());
        }
    }

    // ========================================
    // processSingleListing - 個別処理
    // ========================================

    @Nested
    @DisplayName("processSingleListing - 募集1件の自動キャンセル処理")
    class ProcessSingleListing {

        @Test
        @DisplayName("PERSONAL札も期限超過時に自動キャンセルする")
        void processSingleListing_personal_autoCancels() throws Exception {
            RecruitmentListingEntity listing = buildOpenListing(10, 0, 5);
            setField(listing, "scopeType", RecruitmentScopeType.PERSONAL);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(participantRepository.findByListingIdAndStatusIn(anyLong(), any(), any()))
                    .willReturn(Page.empty());

            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            assertThat(result).isZero();
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED);
            verify(listingRepository).save(listing);
            verify(confirmableNotificationService, never()).send(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("OPEN 状態の listing + CONFIRMED 参加者 → 自動キャンセル実行")
        void processSingleListing_openListing_cancelledWithParticipants() throws Exception {
            // given
            // confirmedCount=1, minCapacity=5 → 最小定員未達
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            // CONFIRMED 参加者1件 (totalElements=1, size=100 → hasNext()=false)
            RecruitmentParticipantEntity participant = buildParticipant(99L, RecruitmentParticipantStatus.CONFIRMED);
            Page<RecruitmentParticipantEntity> firstPage = new PageImpl<>(
                    List.of(participant), PageRequest.of(0, 100), 1);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), eq(PageRequest.of(0, 100)))).willReturn(firstPage);

            given(participantRepository.save(any())).willReturn(participant);
            given(historyRepository.save(any())).willReturn(null);
            given(listingRepository.save(any())).willReturn(listing);

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: 参加者1件がキャンセルされ、listing も AUTO_CANCELLED に
            assertThat(result).isEqualTo(1);
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED);
            verify(participantRepository).save(participant);
            verify(historyRepository).save(any());
        }

        @Test
        @DisplayName("CANCELLED 状態 → スキップ（0件返す）")
        void processSingleListing_alreadyNotOpen_skips() throws Exception {
            // given: ステータスが既に CANCELLED
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 5);
            setField(listing, "status", RecruitmentListingStatus.CANCELLED);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: スキップなので 0
            assertThat(result).isEqualTo(0);
            // 参加者への操作は一切なし
            verify(participantRepository, never()).findByListingIdAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("confirmedCount >= minCapacity → スキップ（最小定員達成）")
        void processSingleListing_minCapacityMet_skips() throws Exception {
            // given: confirmedCount=5, minCapacity=5 → 最小定員達成
            RecruitmentListingEntity listing = buildOpenListing(10, 5, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: スキップ
            assertThat(result).isEqualTo(0);
            verify(participantRepository, never()).findByListingIdAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("参加者なし → listing のみキャンセル、通知なし")
        void processSingleListing_noParticipants_cancelsListingOnly() throws Exception {
            // given: 参加者ゼロ、最小定員未達
            RecruitmentListingEntity listing = buildOpenListing(10, 0, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            // 参加者ページが最初から空
            Page<RecruitmentParticipantEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 100), 0);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), any(Pageable.class))).willReturn(emptyPage);

            given(listingRepository.save(any())).willReturn(listing);

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: キャンセル件数は 0、listing は AUTO_CANCELLED
            assertThat(result).isEqualTo(0);
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED);
            // 参加者への save なし
            verify(participantRepository, never()).save(any());
            // 通知なし（affectedUserIds が空）
            verify(confirmableNotificationService, never()).send(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("CHUNK_SIZE(100件)を超える参加者 → 全員キャンセルされる（縮小クエリのドレイン挙動を再現）")
        void processSingleListing_moreThanChunkSize_allParticipantsCancelled() throws Exception {
            // given: 250人（CHUNK_SIZE=100の2.5倍）
            RecruitmentListingEntity listing = buildOpenListing(300, 1, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            int participantCount = 250;
            List<RecruitmentParticipantEntity> remaining = new ArrayList<>();
            for (int i = 0; i < participantCount; i++) {
                remaining.add(buildParticipant((long) (i + 1), RecruitmentParticipantStatus.CONFIRMED));
            }

            // 実DBの挙動を再現: クエリは常に「現時点で対象ステータスの行」の先頭ページを返す。
            // save() でステータスがCANCELLEDになった行は、以後のクエリ結果から自然に除外される。
            given(participantRepository.findByListingIdAndStatusIn(eq(LISTING_ID), any(), any(PageRequest.class)))
                    .willAnswer(invocation -> {
                        PageRequest pageRequest = invocation.getArgument(2);
                        // page番号を進めて叩いてきた場合は不正利用として空ページを返す
                        // （常にpage=0で叩くべき、というのが本テストの検証対象）
                        if (pageRequest.getPageNumber() != 0) {
                            return new PageImpl<>(Collections.emptyList(), pageRequest, remaining.size());
                        }
                        int size = pageRequest.getPageSize();
                        List<RecruitmentParticipantEntity> content = remaining.stream()
                                .limit(size)
                                .collect(Collectors.toList());
                        return new PageImpl<>(content, pageRequest, remaining.size());
                    });

            given(participantRepository.save(any())).willAnswer(invocation -> {
                RecruitmentParticipantEntity saved = invocation.getArgument(0);
                // ステータスがCANCELLEDになった参加者は対象集合から取り除く（実DBの絞り込みを模す）
                remaining.removeIf(p -> p.getId().equals(saved.getId()));
                return saved;
            });
            given(historyRepository.save(any())).willReturn(null);
            given(listingRepository.save(any())).willReturn(listing);

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: 250人全員がキャンセルされる（後続100人が読み飛ばされない）
            assertThat(result).isEqualTo(participantCount);
            assertThat(remaining).isEmpty();
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED);
        }

        @Test
        @DisplayName("対象外ステータス（CANCELLED済み）の参加者はキャンセル対象クエリに含まれないためキャンセルされない")
        void processSingleListing_nonTargetStatusParticipant_notCancelled() throws Exception {
            // given: クエリは CANCEL_TARGET_STATUSES に絞り込まれる前提のため、
            // 既にCANCELLEDの参加者はそもそもfindByListingIdAndStatusInの結果に現れない。
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            RecruitmentParticipantEntity alreadyCancelled =
                    buildParticipant(77L, RecruitmentParticipantStatus.CANCELLED);

            // 対象外ステータスの参加者はクエリ結果に含まれない（空ページ）ことをシミュレート
            Page<RecruitmentParticipantEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 100), 0);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), any(Pageable.class))).willReturn(emptyPage);
            given(listingRepository.save(any())).willReturn(listing);

            // when
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: 対象外ステータスの参加者はキャンセルされず、処理件数は0
            assertThat(result).isEqualTo(0);
            assertThat(alreadyCancelled.getStatus()).isEqualTo(RecruitmentParticipantStatus.CANCELLED);
            verify(participantRepository, never()).save(alreadyCancelled);
        }

        @Test
        @DisplayName("進捗ゼロが続く異常系 → 無限ループにならず安全弁で中断する")
        void processSingleListing_noProgress_breaksViaSafetyValve() throws Exception {
            // given: クエリが常に同じ1件を返し続ける（ステータス遷移が反映されない異常状態を模す）
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            RecruitmentParticipantEntity stuckParticipant =
                    buildParticipant(55L, RecruitmentParticipantStatus.CONFIRMED);
            Page<RecruitmentParticipantEntity> stuckPage = new PageImpl<>(
                    List.of(stuckParticipant), PageRequest.of(0, 100), 1);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), any(Pageable.class))).willReturn(stuckPage);
            given(participantRepository.save(any())).willReturn(stuckParticipant);
            given(historyRepository.save(any())).willReturn(null);
            given(listingRepository.save(any())).willReturn(listing);

            // when: 無限ループにならず、有限時間で結果が返ってくることを検証する
            int result = batch.processSingleListing(LISTING_ID, LocalDateTime.now());

            // then: 1件目は処理されるが、2周目以降は同じ参加者が再抽出されるため
            // 進捗ゼロと判定され安全弁で中断する（無限ループに陥らない）
            assertThat(result).isEqualTo(1);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * テスト用 OPEN 状態の listing を構築する。
     */
    private RecruitmentListingEntity buildOpenListing(int capacity, int confirmedCount, int minCapacity) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .categoryId(10L)
                .title("テスト募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .applicationDeadline(LocalDateTime.now().minusHours(1))
                .autoCancelAt(LocalDateTime.now().minusMinutes(30))
                .capacity(capacity)
                .minCapacity(minCapacity)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(1L)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        setField(listing, "confirmedCount", confirmedCount);
        return listing;
    }

    /**
     * テスト用参加者エンティティを構築する。
     */
    private RecruitmentParticipantEntity buildParticipant(Long userId, RecruitmentParticipantStatus status) throws Exception {
        RecruitmentParticipantEntity p = RecruitmentParticipantEntity.builder()
                .listingId(LISTING_ID)
                .participantType(RecruitmentParticipantType.USER)
                .userId(userId)
                .appliedBy(userId)
                .status(status)
                .build();
        setField(p, "id", userId * 10L);
        return p;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
