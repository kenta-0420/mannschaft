package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F03.11.1 募集キャンセル料の免除（waive）（設計書 §10）。
 *
 * <p>免除は必ず債権の放棄を行うが、申込ブロックの解除は「そのユーザーに他の未払いが残っていない場合」に限る。
 * ブロックの判定がユーザー単位（{@code existsByUserIdAndPaymentStatusIn}）である一方、免除は記録を 1 件ずつ
 * 指定して行うためである（§10.0）。免除の効果は免除した主催者の募集に閉じず、ブロックが実際に外れたときは
 * あらゆる募集に対して外れる。</p>
 *
 * <p>免除できるのは受取先側（TEAM の支払い管理権限者 / ORG の管理者 / 個人受取の本人）と
 * {@code SYSTEM_ADMIN} である。受取先の判定は escrow の payee に基づかせ、募集の作成者では判定しない
 * ——募集を作った者と謝礼の受取先は一致するとは限らず、作成者で判定すると免除できる範囲が受取先と食い違う。
 * キャンセル料を負っている本人は免除できない（債務者が自分の債務を消せてはならない・§10.2）。</p>
 *
 * <h3>認可は tx の外（CMP-260923-0954 W4）</h3>
 * <p>本クラスは<b>tx 本体</b>であり、認可（{@code AccessControlService}・受取先判定）には一切依存しない。
 * 認可は {@link RecruitmentMoneyFacade#waive} が tx の外で行い、結果（受取先側か）を引数で受け取る。
 * 本クラスは認可の後に「記録→募集」を全部たどり直し、どれかが不在なら {@code COMMON_005}(404) で DB 不変のまま止める
 * （認可の後・tx の前に記録・募集が消える競合への備え）。</p>
 *
 * <p><b>モデレーション非表示の募集</b>: たどり直しは論理削除（{@code deleted_at}）だけを見る
 * （{@link RecruitmentListingRepository#lockLiveListingIdIgnoringModeration}）。是正前の免除は記録だけを読んで
 * 募集を読まなかったため、モデレーション非表示の募集にぶら下がる記録も免除できていた。
 * {@code RecruitmentListingEntity} の {@code @SQLRestriction}（{@code moderation_hidden_at IS NULL} を含む）に
 * 頼るとこの挙動が変わってしまうので、エンティティ経由で募集を引いてはならない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentCancellationFeeWaiveService {

    /** 免除理由の最大長（{@code notes VARCHAR(500)} に収まる長さ）。 */
    private static final int MAX_REASON_LENGTH = 500;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final RecruitmentListingRepository listingRepository;
    private final AuditLogService auditLogService;

    /**
     * 免除対象の記録の最小情報（認可の入力）。募集は含まない（許可経路で募集を読まないため）。
     *
     * @param recordId      記録 ID
     * @param listingId     記録の募集 ID
     * @param participantId 記録の参加者 ID
     * @param debtorUserId  キャンセル料を負っている本人
     */
    public record WaiveTarget(Long recordId, Long listingId, Long participantId, Long debtorUserId) {
    }

    /**
     * 募集のスコープ（拒否経路で「存在を知り得る者か」を判定するためだけに使う）。
     *
     * @param scopeType 募集のスコープ種別
     * @param scopeId   募集のスコープ ID
     */
    public record ListingScope(RecruitmentScopeType scopeType, Long scopeId) {
    }

    /**
     * 免除理由の形式検査（存在判定より前・ID 非依存。違反は 400）。
     *
     * @param reason 免除理由
     */
    public static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    /**
     * 認可の前に記録を解決する（readOnly。自ドメインの Repository のみ）。
     *
     * @param recordId 記録 ID
     * @return 認可の入力
     * @throws BusinessException 不在・論理削除済みは {@code COMMON_005}(404)
     */
    @Transactional(readOnly = true)
    public WaiveTarget resolveWaiveTarget(Long recordId) {
        RecruitmentCancellationRecordEntity record = cancellationRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_005));
        return new WaiveTarget(record.getId(), record.getListingId(), record.getParticipantId(), record.getUserId());
    }

    /**
     * 募集のスコープを引く（拒否経路でだけ呼ぶ。論理削除だけを見て、モデレーション非表示は不在にしない）。
     *
     * @param listingId 募集 ID
     * @return スコープ。募集が論理削除済み・不在なら空
     */
    @Transactional(readOnly = true)
    public Optional<ListingScope> resolveListingScope(Long listingId) {
        return listingRepository.findModerationListingById(listingId)
                .map(p -> new ListingScope(RecruitmentScopeType.valueOf(p.getScopeType()), p.getScopeId()));
    }

    /**
     * キャンセル料を免除する。
     *
     * @param recordId    対象のキャンセル記録 ID
     * @param actorUserId 操作者ユーザー ID
     * @param reason      免除理由（必須・最大 500 文字）
     * @param payeeSide   認可の結果。受取先側の精算管理者として許可されたなら true、SYSTEM_ADMIN として許可されたなら false
     *                    （監査ログの {@code operatorRole} にだけ使う。認可は呼び出し側 Facade が済ませている）
     */
    @Transactional
    public void waive(Long recordId, Long actorUserId, String reason, boolean payeeSide) {
        validateReason(reason);

        // 記録→募集を認可の後にたどり直す。どれかが不在（認可の後に消えた）なら対象の不在コードで 404・DB 不変。
        // 存在しない記録・論理削除済みの記録は 404（存在を推測させない）。
        RecruitmentCancellationRecordEntity record = cancellationRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_005));
        // 募集の行ロック（FOR UPDATE）は認可の後だけ。論理削除だけを見る（モデレーション非表示は通す）。
        // 記録の scope（募集）は不変という前提。募集の論理削除（archive）とは行ロックで直列化される。
        listingRepository.lockLiveListingIdIgnoringModeration(record.getListingId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_005));

        // 既に免除済みなら冪等に成功で返す（終端状態なら何でも 409、にはしない）。
        if (record.getPaymentStatus() == CancellationPaymentStatus.WAIVED) {
            log.info("F03.11.1 キャンセル料は既に免除済み（冪等・no-op）: recordId={}", recordId);
            return;
        }
        // 徴収済みのものは免除ではなく返金の話であり、混同させない。
        if (record.getPaymentStatus() == CancellationPaymentStatus.PAID) {
            throw new BusinessException(RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID);
        }

        CancellationPaymentStatus previousStatus = record.getPaymentStatus();
        record.waive(actorUserId, reason);
        cancellationRecordRepository.save(record);

        // 免除は金銭債権を消す操作である。誰がいつ何円を消したかを後から追えないまま実行させてはならない（§10.4）。
        auditLogService.record(
                AuditEventType.RECRUITMENT_CANCELLATION_FEE_WAIVED.name(),
                actorUserId,
                record.getUserId(),
                record.getTeamId(),
                null,
                null,
                null,
                null,
                String.format(
                        "{\"recordId\":%d,\"feeAmount\":%d,\"previousStatus\":\"%s\",\"operatorRole\":\"%s\"}",
                        recordId, record.getFeeAmount(), previousStatus,
                        payeeSide ? "PAYEE_SIDE" : "SYSTEM_ADMIN"));

        log.info("F03.11.1 キャンセル料を免除: recordId={}, actorUserId={}, feeAmount={}, 免除前={}",
                recordId, actorUserId, record.getFeeAmount(), previousStatus);
    }
}
