package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link RecruitmentNoShowService} の単体テスト。
 * §5.8 NO_SHOW フローの主要パスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentNoShowService 単体テスト")
class RecruitmentNoShowServiceTest {

    @Mock
    private RecruitmentParticipantRepository participantRepository;

    @Mock
    private RecruitmentNoShowRecordRepository noShowRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RecruitmentNoShowService service;

    private static final Long ADMIN_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long PARTICIPANT_ID = 100L;
    private static final Long RECORD_ID = 200L;
    private static final Long LISTING_ID = 500L;
    private static final Long PENALIZED_USER_ID = 999L;
    private static final RecruitmentScopeType SCOPE_TYPE = RecruitmentScopeType.TEAM;

    // ========================================
    // resolveDispute - §5.8 異議申立解決
    // ========================================

    @Nested
    @DisplayName("resolveDispute - §5.8 異議申立解決")
    class ResolveDispute {

        @Test
        @DisplayName("認可チェック失敗 → BusinessException が伝播")
        void resolveDispute_unauthorizedAdmin_throws() {
            // checkAdminOrAbove は権限なし時に COMMON_002 を投げる
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_ID, SCOPE_ID, SCOPE_TYPE.name());

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("NO_SHOW 記録が存在しない → NO_SHOW_RECORD_NOT_FOUND")
        void resolveDispute_recordNotFound_throws() {
            given(noShowRepository.findByIdAndScopeTypeAndScopeId(RECORD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND);
        }

        @Test
        @DisplayName("異議申立中でない記録 → INVALID_STATE_TRANSITION")
        void resolveDispute_notDisputedRecord_throws() throws Exception {
            RecruitmentNoShowRecordEntity record = buildRecord(false);
            given(noShowRepository.findByIdAndScopeTypeAndScopeId(RECORD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(record));

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        /**
         * 裏目付C 根治の要: 対象記録が別スコープに属する場合、スコープ済みクエリが空を返し
         * 不在と同一の {@code NO_SHOW_RECORD_NOT_FOUND} に畳み込まれること（ID 実在の秘匿）。
         *
         * <p><b>【重要・読み違え防止 #2497】このケースは「越境フィルタそのもの」を検証していない。</b>
         * ここでは {@code noShowRepository} がモックであり、「越境時に空を返す」という前提を
         * テスト側が {@code given(...).willReturn(Optional.empty())} で<b>与えている</b>。
         * 従って本ケースが固定しているのは
         * 「スコープ済みクエリが空を返したとき、Service が不在と同一のエラーコードに畳み込むこと」
         * （＝実在オラクル封じの Service 側責務）だけである。
         * {@code findByIdAndScopeTypeAndScopeId} の JOIN 条件が本当に他スコープの記録を
         * 弾くかどうかは、実 MySQL を使う契約 IT
         * {@code com.mannschaft.app.recruitment.RecruitmentNoShowScopeContractIT}（AC-R1/AC-R2/AC-R6）
         * が唯一の実証である。本 UT だけを見て「越境は検証済み」と判断してはならない。</p>
         */
        @Test
        @DisplayName("別スコープの記録IDは不在と同一の NO_SHOW_RECORD_NOT_FOUND（畳み込みのみ検証・越境実証はIT）")
        void resolveDispute_crossScopeRecord_throwsSameAsAbsent() {
            // 自スコープ権限は通るが、スコープ済みクエリが越境IDを空に畳み込む
            given(noShowRepository.findByIdAndScopeTypeAndScopeId(RECORD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.REVOKED))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND);
        }
    }

    // ========================================
    // markNoShow - §5.8 NO_SHOW マーク
    // ========================================

    @Nested
    @DisplayName("markNoShow - §5.8 NO_SHOW マーク")
    class MarkNoShow {

        @Test
        @DisplayName("対象参加者が存在しない → LISTING_NOT_FOUND")
        void markNoShow_participantNotFound_throws() {
            given(participantRepository.findById(PARTICIPANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.markNoShow(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
    }

    // ========================================
    // dispute - #2497 archive 済み募集枠への新規申立
    // ========================================

    /**
     * archive 済み募集枠に対して後から申し立てられた異議を、その場で取り下げる責務。
     *
     * <p>実 DB での実証（`@SQLRestriction` の迂回・射影マッピング・EP 越しの往復）は
     * {@code com.mannschaft.app.recruitment.RecruitmentListingArchiveDisputeAutoRevokeIT}
     * の AC-6〜AC-10 が担う。ここは分岐と監査引数のみを固定する。</p>
     */
    @Nested
    @DisplayName("dispute - #2497 archive 済み募集枠への新規申立は即時取下げ")
    class DisputeAfterListingArchived {

        @Test
        @DisplayName("archive 済みなら申立を受け付けたうえで即座に REVOKED を当てる")
        void archive済みなら即時REVOKED() throws Exception {
            RecruitmentNoShowRecordEntity record = buildUndisputedRecord();
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.of(record));
            given(listingRepository.findArchivedScopeById(LISTING_ID))
                    .willReturn(Optional.of(archivedScope(RecruitmentScopeType.TEAM, SCOPE_ID)));

            service.dispute(RECORD_ID, PENALIZED_USER_ID);

            assertThat(record.isDisputed())
                    .as("申立自体は受け付ける（拒否は利用者から救済手段を奪う）")
                    .isTrue();
            assertThat(record.getDisputeResolution())
                    .as("裁定経路が塞がっている以上、その場で認容（REVOKED）として取り下げる")
                    .isEqualTo(DisputeResolution.REVOKED);
        }

        @Test
        @DisplayName("archive 済みの取下げは trigger=DISPUTED_AFTER_LISTING_ARCHIVED で監査に残る")
        void archive済みの取下げは監査に残る() throws Exception {
            RecruitmentNoShowRecordEntity record = buildUndisputedRecord();
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.of(record));
            given(listingRepository.findArchivedScopeById(LISTING_ID))
                    .willReturn(Optional.of(archivedScope(RecruitmentScopeType.TEAM, SCOPE_ID)));

            service.dispute(RECORD_ID, PENALIZED_USER_ID);

            ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("RECRUITMENT_NO_SHOW_DISPUTE_AUTO_REVOKED"),
                    eq(PENALIZED_USER_ID),      // 申立を行った利用者本人が操作者
                    eq(PENALIZED_USER_ID),      // 対象も本人
                    eq(SCOPE_ID),               // TEAM スコープ → teamId
                    isNull(),
                    isNull(), isNull(), isNull(),
                    metadata.capture());
            assertThat(metadata.getValue())
                    .as("archive 時の一括取下げ（LISTING_ARCHIVED）と区別できること")
                    .contains("\"trigger\":\"DISPUTED_AFTER_LISTING_ARCHIVED\"")
                    .contains("\"listingId\":" + LISTING_ID);
        }

        @Test
        @DisplayName("ORGANIZATION スコープでも監査の organizationId 側に振り分けられる")
        void archive済みORGANIZATIONスコープの振り分け() throws Exception {
            RecruitmentNoShowRecordEntity record = buildUndisputedRecord();
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.of(record));
            given(listingRepository.findArchivedScopeById(LISTING_ID))
                    .willReturn(Optional.of(archivedScope(RecruitmentScopeType.ORGANIZATION, SCOPE_ID)));

            service.dispute(RECORD_ID, PENALIZED_USER_ID);

            verify(auditLogService).record(
                    eq("RECRUITMENT_NO_SHOW_DISPUTE_AUTO_REVOKED"),
                    eq(PENALIZED_USER_ID), eq(PENALIZED_USER_ID),
                    isNull(), eq(SCOPE_ID),
                    isNull(), isNull(), isNull(),
                    anyString());
        }

        @Test
        @DisplayName("生存中の募集枠なら従来どおり disputed=true / resolution=null・監査も打たない（非回帰）")
        void 生存中なら従来どおり() throws Exception {
            RecruitmentNoShowRecordEntity record = buildUndisputedRecord();
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.of(record));
            given(listingRepository.findArchivedScopeById(LISTING_ID)).willReturn(Optional.empty());

            service.dispute(RECORD_ID, PENALIZED_USER_ID);

            assertThat(record.isDisputed()).isTrue();
            assertThat(record.getDisputeResolution())
                    .as("生存中なら管理者が裁定できるので自動取下げしてはならない")
                    .isNull();
            verifyNoInteractions(auditLogService);
        }
    }

    // ========================================
    // autoRevokeOpenDisputesOnListingArchived - #2497 募集枠論理削除に伴う自動取下げ
    // ========================================

    /**
     * #2497 の Service 側責務のうち、<b>実 DB を要しない部分</b>を固定する。
     *
     * <p>本 Nested が検証するのは
     * ①取得された未解決異議に当てる値が {@code REVOKED} であること、
     * ②監査ログのイベント種別・操作者・対象ユーザー・スコープ振り分けが正しいこと、
     * ③0 件のとき副作用が一切起きないこと の 3 点である。</p>
     *
     * <p><b>フィルタ条件（{@code disputed = TRUE AND dispute_resolution IS NULL}）そのものは
     * ここでは検証できない。</b> 派生クエリの絞り込みが実際に効くか（解決済み・非異議の記録が
     * 巻き込まれないか）は実 MySQL の
     * {@code com.mannschaft.app.recruitment.RecruitmentListingArchiveDisputeAutoRevokeIT} が実証する。</p>
     */
    @Nested
    @DisplayName("autoRevokeOpenDisputesOnListingArchived - #2497 募集枠論理削除に伴う異議の自動取下げ")
    class AutoRevokeOpenDisputesOnListingArchived {

        @Test
        @DisplayName("未解決の異議に REVOKED を当てて保存する")
        void 未解決異議にREVOKEDを当てて保存する() throws Exception {
            RecruitmentNoShowRecordEntity record = buildOpenDisputeRecord();
            given(noShowRepository.findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(LISTING_ID))
                    .willReturn(List.of(record));

            int revoked = service.autoRevokeOpenDisputesOnListingArchived(
                    LISTING_ID, SCOPE_TYPE, SCOPE_ID, ADMIN_ID);

            assertThat(revoked).isEqualTo(1);
            assertThat(record.getDisputeResolution())
                    .as("募集枠が消えて裁定不能になる以上、利用者に有利な REVOKED を当てる")
                    .isEqualTo(DisputeResolution.REVOKED);
            assertThat(record.isDisputed())
                    .as("異議を申し立てた事実自体は履歴として残す")
                    .isTrue();
            verify(noShowRepository).saveAll(List.of(record));
        }

        @Test
        @DisplayName("TEAM スコープ: 監査ログに操作者・対象ユーザー・teamId が残る")
        void 監査ログにTEAMスコープの文脈が残る() throws Exception {
            RecruitmentNoShowRecordEntity record = buildOpenDisputeRecord();
            given(noShowRepository.findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(LISTING_ID))
                    .willReturn(List.of(record));

            service.autoRevokeOpenDisputesOnListingArchived(LISTING_ID, RecruitmentScopeType.TEAM, SCOPE_ID, ADMIN_ID);

            ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("RECRUITMENT_NO_SHOW_DISPUTE_AUTO_REVOKED"),
                    eq(ADMIN_ID),               // 誰の操作に起因する取り下げか
                    eq(PENALIZED_USER_ID),      // 誰の NO_SHOW 記録が取り下げられたか
                    eq(SCOPE_ID),               // TEAM スコープ → teamId
                    isNull(),                   // organizationId は null
                    isNull(), isNull(), isNull(),
                    metadata.capture());
            assertThat(metadata.getValue())
                    .as("後から「募集枠の論理削除に伴う自動取下げ」と判別できること")
                    .contains("\"trigger\":\"LISTING_ARCHIVED\"")
                    .contains("\"listingId\":" + LISTING_ID)
                    .contains("\"resolution\":\"REVOKED\"");
        }

        @Test
        @DisplayName("ORGANIZATION スコープ: 監査ログの organizationId 側に振り分けられる")
        void 監査ログにORGANIZATIONスコープの文脈が残る() throws Exception {
            RecruitmentNoShowRecordEntity record = buildOpenDisputeRecord();
            given(noShowRepository.findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(LISTING_ID))
                    .willReturn(List.of(record));

            service.autoRevokeOpenDisputesOnListingArchived(
                    LISTING_ID, RecruitmentScopeType.ORGANIZATION, SCOPE_ID, ADMIN_ID);

            verify(auditLogService).record(
                    eq("RECRUITMENT_NO_SHOW_DISPUTE_AUTO_REVOKED"),
                    eq(ADMIN_ID), eq(PENALIZED_USER_ID),
                    isNull(),                   // teamId は null
                    eq(SCOPE_ID),               // ORGANIZATION スコープ → organizationId
                    isNull(), isNull(), isNull(),
                    anyString());
        }

        @Test
        @DisplayName("未解決の異議が 0 件なら保存も監査も行わない")
        void 対象0件なら副作用なし() {
            given(noShowRepository.findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(LISTING_ID))
                    .willReturn(List.of());

            int revoked = service.autoRevokeOpenDisputesOnListingArchived(
                    LISTING_ID, SCOPE_TYPE, SCOPE_ID, ADMIN_ID);

            assertThat(revoked).isZero();
            verify(noShowRepository, never()).saveAll(any());
            verifyNoInteractions(auditLogService);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentNoShowRecordEntity buildRecord(boolean disputed) throws Exception {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(PARTICIPANT_ID)
                .listingId(1L)
                .userId(999L)
                .build();
        // isDisputed フィールドをリフレクションで設定
        Field f = RecruitmentNoShowRecordEntity.class.getDeclaredField("disputed");
        f.setAccessible(true);
        f.set(record, disputed);
        return record;
    }

    /** 異議未申立（disputed=false / disputeResolution=null）の NO_SHOW 記録。 */
    private RecruitmentNoShowRecordEntity buildUndisputedRecord() throws Exception {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(PARTICIPANT_ID)
                .listingId(LISTING_ID)
                .userId(PENALIZED_USER_ID)
                .build();
        Field idField = RecruitmentNoShowRecordEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(record, RECORD_ID);
        return record;
    }

    /** {@code findArchivedScopeById} の射影スタブ。 */
    private RecruitmentListingRepository.ArchivedListingScope archivedScope(
            RecruitmentScopeType scopeType, Long scopeId) {
        return new RecruitmentListingRepository.ArchivedListingScope() {
            @Override
            public String getScopeType() {
                return scopeType.name();
            }

            @Override
            public Long getScopeId() {
                return scopeId;
            }
        };
    }

    /** 未解決の異議申立（disputed=true / disputeResolution=null）を持つ NO_SHOW 記録。 */
    private RecruitmentNoShowRecordEntity buildOpenDisputeRecord() throws Exception {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(PARTICIPANT_ID)
                .listingId(LISTING_ID)
                .userId(PENALIZED_USER_ID)
                .build();
        record.dispute();
        // 監査ログの metadata 検証用に id を差し込む（IDENTITY 採番の代替）
        Field idField = RecruitmentNoShowRecordEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(record, RECORD_ID);
        return record;
    }
}
