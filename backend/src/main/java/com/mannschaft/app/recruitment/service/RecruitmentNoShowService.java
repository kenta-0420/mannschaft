package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.NoShowReason;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository.ArchivedListingScope;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: NO_SHOW マーク・異議申立サービス。
 *
 * 設計書 §5.8 (NO_SHOW フロー) を参照。
 * 通知は F04.9 実装後に統合予定（現在はログ出力のみ）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentNoShowService {

    /**
     * #2497: 募集枠の論理削除に伴い、未解決の異議申立を自動で取り下げたことを示す監査イベント種別。
     *
     * <p>{@code AuditEventType} enum には登録しない。recruitment 用の
     * {@code AuditEventCategory} が存在せず、カテゴリを新設すると
     * {@code docs/openapi.json} / 生成型のドリフトを招くため、
     * 既存の生文字列イベント（{@code CIRCULATION_FORCE_COMPLETED} 等）と同じ流儀に揃える。</p>
     */
    static final String AUDIT_EVENT_DISPUTE_AUTO_REVOKED = "RECRUITMENT_NO_SHOW_DISPUTE_AUTO_REVOKED";

    /** 監査 metadata の {@code trigger}: 募集枠 archive 時の一括取り下げ。 */
    static final String AUDIT_TRIGGER_LISTING_ARCHIVED = "LISTING_ARCHIVED";

    /** 監査 metadata の {@code trigger}: archive 済み募集枠へ後から申し立てられた異議の即時取り下げ。 */
    static final String AUDIT_TRIGGER_DISPUTED_AFTER_ARCHIVE = "DISPUTED_AFTER_LISTING_ARCHIVED";

    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;
    private final AccessControlService accessControlService;
    /**
     * 募集枠リポジトリ（#2497）。archive 済み募集枠のスコープ取得にのみ使う。
     * 同一 recruitment ドメイン内の依存であり、ドメイン境界を跨がない。
     */
    private final RecruitmentListingRepository listingRepository;
    /** 監査ログサービス（#2497 自動取下げの記録用）。 */
    private final AuditLogService auditLogService;

    // ===========================================
    // 管理者による NO_SHOW マーク
    // ===========================================

    /**
     * 管理者が参加者を NO_SHOW としてマークする（仮マーク = confirmed=false）。
     * 24時間後に確定バッチが confirmed=true にする。
     */
    @Transactional
    public RecruitmentNoShowRecordEntity markNoShow(Long participantId, Long adminUserId) {
        RecruitmentParticipantEntity participant = participantRepository.findById(participantId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        // 権限チェック: 対象募集のスコープ管理者であること
        accessControlService.checkAdminOrAbove(
                adminUserId, participant.getListingId(), "RECRUITMENT");

        // CONFIRMED のみマーク可能
        if (participant.getStatus() != RecruitmentParticipantStatus.CONFIRMED) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // 既に NO_SHOW 記録があれば重複防止
        noShowRepository.findByParticipantId(participantId).ifPresent(r -> {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_DISPUTED);
        });

        // 参加者ステータスを NO_SHOW に変更
        participant.markNoShow();
        participantRepository.save(participant);

        // NO_SHOW 記録を仮マーク（confirmed=false）で作成
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(participantId)
                .listingId(participant.getListingId())
                .userId(participant.getUserId())
                .reason(NoShowReason.ADMIN_MARKED)
                .recordedBy(adminUserId)
                .build();
        RecruitmentNoShowRecordEntity saved = noShowRepository.save(record);

        // TODO: F04.9 実装後に RECRUITMENT_NO_SHOW_RECORDED 通知を送信
        log.info("F03.11 Phase5b NO_SHOW仮マーク: participantId={}, userId={}, recordedBy={}",
                participantId, participant.getUserId(), adminUserId);

        return saved;
    }

    // ===========================================
    // 異議申立
    // ===========================================

    /**
     * ユーザーが自分の NO_SHOW に異議申立を行う。
     * ペナルティ設定の dispute_allowed_days 以内のみ可能。
     *
     * <p><b>#2497: archive 済み募集枠への申立はその場で取り下げる。</b>
     * 募集枠を archive した時点で {@code disputed = FALSE} だった記録は
     * {@link #autoRevokeOpenDisputesOnListingArchived} の対象外である（異議が無いものを
     * 取り消す理由が無いため、これは意図した設計）。しかしその後に利用者が本メソッドで
     * 異議を申し立てると、申立自体は成功する（本メソッドは募集枠を JOIN しない
     * {@code findById} で引くため）一方、裁定側の
     * {@code findByIdAndScopeTypeAndScopeId} は募集枠を JOIN するため
     * <b>永久に裁定不能</b>になり、{@code countConfirmedNoShows} には算入され続ける。
     * これは #2497 と時間軸がずれただけの同一の実害であり、
     * 「archive 時点で未申立 かつ 申立期限（14 日）内」という決して稀でない窓で成立する。</p>
     *
     * <p><b>申立を拒否する</b>のではなく<b>受け付けたうえで即時に取り下げる</b>のは、
     * 拒否が利用者から救済手段を奪うためである。裁定の根拠（募集枠）を消したのは団体側であり、
     * 不利益を利用者に負わせるのは不当。よって
     * {@link DisputeResolution#REVOKED}（異議認容）を当てる。</p>
     */
    @Transactional
    public RecruitmentNoShowRecordEntity dispute(Long recordId, Long userId) {
        RecruitmentNoShowRecordEntity record = noShowRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND));

        // 本人チェック
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(RecruitmentErrorCode.VISIBILITY_DENIED);
        }

        if (record.isDisputed()) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_DISPUTED);
        }

        // 14日以内（固定値、設定から取れる場合は将来改善）
        if (record.getRecordedAt().plusDays(14).isBefore(LocalDateTime.now())) {
            throw new BusinessException(RecruitmentErrorCode.NO_SHOW_DISPUTE_DEADLINE_EXCEEDED);
        }

        record.dispute();

        // #2497: 募集枠が archive 済みなら裁定経路が塞がっているため、その場で取り下げる。
        // 戻り値が存在すること自体が「archive 済み」の信号（生存中なら空）。
        Optional<ArchivedListingScope> archivedScope =
                listingRepository.findArchivedScopeById(record.getListingId());
        archivedScope.ifPresent(scope -> record.resolveDispute(DisputeResolution.REVOKED));

        noShowRepository.save(record);

        archivedScope.ifPresent(scope -> {
            RecruitmentScopeType scopeType = RecruitmentScopeType.valueOf(scope.getScopeType());
            recordAutoRevokeAudit(record, scopeType, scope.getScopeId(), userId,
                    AUDIT_TRIGGER_DISPUTED_AFTER_ARCHIVE);
            log.info("F03.11 Phase5b archive済み募集枠への異議申立を即時取下げ: "
                            + "recordId={}, userId={}, listingId={}",
                    recordId, userId, record.getListingId());
        });

        // TODO: F04.9 実装後に主催者へ RECRUITMENT_NO_SHOW_DISPUTE_RAISED 通知
        log.info("F03.11 Phase5b 異議申立: recordId={}, userId={}", recordId, userId);

        return record;
    }

    /**
     * 管理者が異議申立を解決する。
     * REVOKED の場合、ペナルティ再計算が必要（PenaltyService に委譲）。
     *
     * <p><b>認可は二段構え</b>（裏目付C）:</p>
     * <ol>
     *   <li>パス由来の親スコープに対する管理者権限（{@code checkAdminOrAbove}）</li>
     *   <li>対象記録が<b>当該スコープに帰属するか</b>（{@code findByIdAndScopeTypeAndScopeId}）</li>
     * </ol>
     *
     * <p>1 だけでは「自スコープの管理者が、他スコープの記録 ID を URL に差し込む」テナント越境
     * （BOLA・書き込み）が成立する。兄弟の {@link #getNoShowsByScope} が最初からスコープ済み
     * クエリを使っているのに対し本メソッドだけが {@code findById} 直引きで規律を破っていたため、
     * 同ドメインの {@code RecruitmentListingService#createFromTemplate} /
     * {@code RecruitmentSubcategoryService} と同じ「スコープ済みクエリで畳み込む」型に揃えた。
     * 不在と越境はいずれも {@code NO_SHOW_RECORD_NOT_FOUND} に収束し、ID の実在も秘匿される。</p>
     */
    @Transactional
    public RecruitmentNoShowRecordEntity resolveDispute(
            Long recordId, Long adminUserId,
            RecruitmentScopeType scopeType, Long scopeId,
            DisputeResolution resolution) {
        // §13 認可 (1/2): 当該スコープの管理者権限を確認
        accessControlService.checkAdminOrAbove(adminUserId, scopeId, scopeType.name());

        // §13 認可 (2/2): 対象記録が当該スコープに帰属することを検証（テナント越境の封鎖）
        RecruitmentNoShowRecordEntity record = noShowRepository
                .findByIdAndScopeTypeAndScopeId(recordId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND));

        if (!record.isDisputed()) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        record.resolveDispute(resolution);
        RecruitmentNoShowRecordEntity saved = noShowRepository.save(record);

        log.info("F03.11 Phase5b 異議申立解決: recordId={}, resolution={}", recordId, resolution);

        return saved;
    }

    /**
     * 募集枠の論理削除に伴い、その配下に残る<b>未解決の異議申立</b>を一括で取り下げる（#2497）。
     *
     * <p><b>なぜ必要か</b>: 異議解決 EP が使う
     * {@link RecruitmentNoShowRecordRepository#findByIdAndScopeTypeAndScopeId} は
     * スコープ境界を得るために {@code RecruitmentListingEntity} を JOIN しており、募集枠側の
     * {@code @SQLRestriction("deleted_at IS NULL")} が効く。よって団体が募集枠を論理削除すると、
     * 配下の NO_SHOW 記録は<b>二度と裁定できなくなる</b>。一方
     * {@link RecruitmentNoShowRecordRepository#countConfirmedNoShows} は
     * 「{@code REVOKED} 以外はすべて算入」という条件のため、未解決（{@code dispute_resolution IS NULL}）
     * の記録はペナルティに算入され続ける。放置すると利用者は
     * 「異議を申し立てたのに永久に裁かれず、ペナルティだけ負う」状態に置かれる。</p>
     *
     * <p><b>なぜ {@code REVOKED} か</b>: 団体が募集枠を消して裁定の根拠を失わせた以上、
     * 裁かれないまま利用者にペナルティを負わせるのは不当である。よって利用者に有利な
     * {@link DisputeResolution#REVOKED}（異議認容＝NO_SHOW 取消）を当てる。
     * {@code disputeResolution} の消費者は
     * ①{@code countConfirmedNoShows}（{@code REVOKED} を算入から除外＝本修正の目的）、
     * ②{@code RecruitmentNoShowController} の応答 DTO（表示のみ）の 2 つだけで、
     * 「{@code REVOKED} 件数を団体の不正指標として数える」統計は存在しない。
     * 副次的に {@code RecruitmentPenaltyRecomputeBatch} が翌日の再計算で閾値割れを検知し、
     * 既存ペナルティを {@code DISPUTE_REVOKED} として自動解除する（望ましい方向の連動）。</p>
     *
     * <p><b>非対象</b>: 既に解決済み（{@code REVOKED} / {@code UPHELD}）の記録と、
     * そもそも異議が申し立てられていない（{@code disputed = FALSE}）記録には触れない。
     * 前者は管理者の裁定を上書きしないため、後者は異議なき NO_SHOW を取り消す理由がないため。</p>
     *
     * <p><b>認可</b>: 本メソッドは認可を行わない。呼出元
     * {@code RecruitmentListingService#archive} が募集枠のスコープに対する
     * {@code checkAdminOrAbove} を済ませたうえで呼ぶ「内部専用」の入口であり、
     * Controller から直接到達する経路は無い（{@code feedback_authz_gate_on_public_entry_not_shared_method}
     * の裏返しで、公開入口側にゲートが立っている）。</p>
     *
     * <p><b>ドメイン境界</b>: 参照する Repository は recruitment ドメイン内のみ。
     * {@code AuditLogService} は auth ドメインだが Repository ではなく Service 呼び出しであり、
     * かつ {@code @Async} でトランザクション外に出るため、越境トランザクションにはならない
     * （CLAUDE.md「DB 設計の原則 #5」/ 番人 D-3 の対象外）。</p>
     *
     * @param listingId   論理削除された募集枠 ID
     * @param scopeType   募集枠のスコープ種別（監査ログのコンテキスト用）
     * @param scopeId     募集枠のスコープ ID（監査ログのコンテキスト用）
     * @param actorUserId 論理削除を実行した管理者ユーザー ID（監査ログの操作者）
     * @return 取り下げた件数
     */
    @Transactional
    public int autoRevokeOpenDisputesOnListingArchived(
            Long listingId, RecruitmentScopeType scopeType, Long scopeId, Long actorUserId) {
        List<RecruitmentNoShowRecordEntity> openDisputes =
                noShowRepository.findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(listingId);
        if (openDisputes.isEmpty()) {
            return 0;
        }

        for (RecruitmentNoShowRecordEntity record : openDisputes) {
            record.resolveDispute(DisputeResolution.REVOKED);
        }
        noShowRepository.saveAll(openDisputes);

        for (RecruitmentNoShowRecordEntity record : openDisputes) {
            recordAutoRevokeAudit(record, scopeType, scopeId, actorUserId, AUDIT_TRIGGER_LISTING_ARCHIVED);
        }

        log.info("F03.11 Phase5b 募集枠論理削除に伴う異議自動取下げ: listingId={}, actorUserId={}, count={}",
                listingId, actorUserId, openDisputes.size());
        return openDisputes.size();
    }

    /**
     * 異議の自動取り下げ 1 件分を監査ログに記録する（#2497）。
     *
     * <p>金型は {@code CirculationService#forceCompleteDocument}
     * （スコープ TEAM/ORGANIZATION を {@code teamId}/{@code organizationId} に振り分け・JSON メタデータ）。
     * 1 記録 = 1 行で {@code targetUserId} を残し、「誰の操作で・誰のどの記録が」
     * 取り下げられたかを後から追えるようにする。</p>
     *
     * <p>{@code trigger} で発生経路を区別する:</p>
     * <ul>
     *   <li>{@link #AUDIT_TRIGGER_LISTING_ARCHIVED} — 募集枠 archive 時の一括取り下げ。
     *       {@code actorUserId} は archive を実行した管理者</li>
     *   <li>{@link #AUDIT_TRIGGER_DISPUTED_AFTER_ARCHIVE} — archive 済み募集枠へ後から
     *       申し立てられた異議の即時取り下げ。{@code actorUserId} は申立を行った利用者本人
     *       （archive の実行者は {@code recruitment_listings} に保持されておらず辿れない。
     *       原因が archive であることは {@code trigger} が示す）</li>
     * </ul>
     */
    private void recordAutoRevokeAudit(
            RecruitmentNoShowRecordEntity record, RecruitmentScopeType scopeType, Long scopeId,
            Long actorUserId, String trigger) {
        auditLogService.record(
                AUDIT_EVENT_DISPUTE_AUTO_REVOKED, actorUserId, record.getUserId(),
                RecruitmentScopeType.TEAM == scopeType ? scopeId : null,
                RecruitmentScopeType.ORGANIZATION == scopeType ? scopeId : null,
                null, null, null,
                "{\"noShowRecordId\":" + record.getId()
                        + ",\"listingId\":" + record.getListingId()
                        + ",\"scopeType\":\"" + scopeType.name() + "\""
                        + ",\"scopeId\":" + scopeId
                        + ",\"resolution\":\"" + DisputeResolution.REVOKED.name() + "\""
                        + ",\"trigger\":\"" + trigger + "\"}");
    }

    // ===========================================
    // 照会
    // ===========================================

    /** ユーザー自身の NO_SHOW 履歴取得。 */
    public List<RecruitmentNoShowRecordEntity> getMyHistory(Long userId) {
        return noShowRepository.findByUserId(userId);
    }

    /** スコープの NO_SHOW 記録一覧（管理者用）。 */
    public List<RecruitmentNoShowRecordEntity> getNoShowsByScope(
            com.mannschaft.app.recruitment.RecruitmentScopeType scopeType, Long scopeId, Long adminUserId) {
        accessControlService.checkAdminOrAbove(adminUserId, scopeId, scopeType.name());
        return noShowRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
    }
}
