package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.dto.CreateTournamentFeeRequest;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeResponse;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 大会参加費ファサードサービス（F08.7.1/07）。
 *
 * <p><strong>新規の汎用決済基盤は作らない。</strong> 大会参加費は F08.2 の {@code payment_items} /
 * {@code member_payments} / Stripe Checkout / MANUAL 記録 / アクセス制御 / grace_period / webhook /
 * 返金（REFUNDED/CANCELLED）をそのまま再利用する。本サービスは「大会／ディビジョンと payment_item を
 * 薄い連結テーブル {@code tournament_fee} で結ぶ」ファサードに徹し、支払いの実処理は
 * {@link MemberPaymentService} に委譲する。</p>
 *
 * <h2>認可（設計書 §6）</h2>
 * <ul>
 *   <li>参加費の作成／更新／削除: 主催組織 ADMIN ／ SYSTEM_ADMIN。</li>
 *   <li>自チーム分の支払い（checkout）: 当該チームの ADMIN/DEPUTY_ADMIN のみ。</li>
 * </ul>
 *
 * <p>存在しない／論理削除済み／他組織の fee・大会は一律 404（IDOR 対策）。</p>
 *
 * <p><strong>越境（原則5）TODO:</strong> 本サービスは tournament ドメインから payment ドメインの
 * {@code PaymentItemService} / {@code MemberPaymentService} / {@code MemberPaymentRepository} を直接呼ぶ。
 * 参加費の連結は読み取り主体で結合度が低いため当面は直接呼び出しとし、将来は
 * {@code TournamentFeeCreatedEvent} 等によるイベント駆動化を検討する。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/07_tournament_payment.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentFeeService {

    private final TournamentFeeRepository feeRepository;
    private final TournamentFeeTargetRepository feeTargetRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final AccessControlService accessControlService;
    // --- payment ドメインへの越境（原則5 TODO・上記クラスコメント参照） ---
    private final PaymentItemService paymentItemService;
    private final MemberPaymentService memberPaymentService;
    private final MemberPaymentRepository memberPaymentRepository;

    // ========================================================================
    // 参加費の作成・一覧・削除（主催組織 ADMIN）
    // ========================================================================

    /**
     * 大会参加費を作成する（主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * <p>payment_item は F08.2 で作成済みのものを連結する。本メソッドは payment_item を新規作成しない
     * （金額・通貨・Stripe 情報の二重管理を避けるため）。</p>
     *
     * @throws BusinessException FEE_MANAGE_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）／
     *                           DIVISION_NOT_FOUND（404）／FEE_PAYMENT_ITEM_SCOPE_MISMATCH（422）
     */
    @Transactional
    public TournamentFeeResponse createFee(Long organizationId, Long tournamentId, Long userId,
                                           CreateTournamentFeeRequest request) {
        TournamentEntity tournament = findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);

        // ディビジョン指定時は当該大会配下であることを検証
        if (request.getDivisionId() != null) {
            verifyDivisionBelongsToTournament(request.getDivisionId(), tournamentId);
        }

        // payment_item が主催組織に属することを検証（クロス組織の流用を防ぐ）
        PaymentItemEntity paymentItem = requireOrganizationPaymentItem(request.getPaymentItemId(), organizationId);

        TournamentFeeTargetScope scope = request.getTargetScope() != null
                ? TournamentFeeTargetScope.valueOf(request.getTargetScope())
                : TournamentFeeTargetScope.ALL_TEAMS;

        TournamentFeeEntity fee = TournamentFeeEntity.builder()
                .tournamentId(tournamentId)
                .divisionId(request.getDivisionId())
                .paymentItemId(request.getPaymentItemId())
                .title(request.getTitle())
                .targetScope(scope)
                .paymentDue(request.getPaymentDue())
                .organizationId(organizationId)
                .createdBy(userId)
                .build();
        TournamentFeeEntity saved = feeRepository.save(fee);

        List<Long> targetTeamIds = persistTargets(saved.getId(), scope, request.getTeamIds());

        log.info("大会参加費作成: feeId={}, tournamentId={}, paymentItemId={}, scope={}",
                saved.getId(), tournamentId, request.getPaymentItemId(), scope);
        return TournamentFeeResponse.of(saved, targetTeamIds, paymentItem.getAmount(), paymentItem.getCurrency());
    }

    /**
     * 大会の参加費一覧（全件）を取得する（主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * <p>本一覧は全チーム分の参加費額・対象チーム一覧を含む「全件閲覧」（設計書 §6
     * 「支払い状況の閲覧: 全件＝主催組織 ADMIN」）に該当するため、主催組織 ADMIN 限定とする。
     * 認証さえあれば誰でも金額・対象チームを取得できる情報開示を防ぐ（IDOR/情報開示対策）。</p>
     *
     * <p>設計書 §6 の「自チーム分＝当該チーム ADMIN/DEPUTY が閲覧」は別エンドポイントの責務であり、
     * 必要になった時点で当該チーム単位のスコープ付き EP を別途設ける（本メソッドでは過剰実装しない）。</p>
     *
     * @throws BusinessException FEE_MANAGE_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）
     */
    public List<TournamentFeeResponse> listFees(Long organizationId, Long tournamentId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        return feeRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 大会参加費を論理削除する（主催組織 ADMIN / SYSTEM_ADMIN）。対象チーム明細も連鎖削除する。
     */
    @Transactional
    public void deleteFee(Long organizationId, Long tournamentId, UUID feeId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        TournamentFeeEntity fee = findFeeInScopeOrThrow(feeId, organizationId, tournamentId);

        feeTargetRepository.deleteByFeeId(fee.getId());
        fee.softDelete();
        feeRepository.save(fee);
        log.info("大会参加費削除: feeId={}", feeId);
    }

    // ========================================================================
    // 支払い導線（自チーム ADMIN/DEPUTY_ADMIN）
    // ========================================================================

    /**
     * 自チーム分の参加費を Stripe Checkout で支払う（自チーム ADMIN/DEPUTY_ADMIN のみ）。
     *
     * <p>実処理は F08.2 の {@link MemberPaymentService#createCheckout(Long, Long)} に委譲する。
     * 本メソッドは「fee → payment_item の解決」と「支払い者＝対象チーム代表」の認可ゲートのみを担う。</p>
     *
     * @throws BusinessException FEE_NOT_FOUND（404）／FEE_PAY_FORBIDDEN（403・他チーム代表でない）／
     *                           FEE_TEAM_NOT_TARGET（403・SPECIFIC_TEAMS の対象外）
     */
    @Transactional
    public CheckoutResponse checkout(Long organizationId, Long tournamentId, UUID feeId, Long teamId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        TournamentFeeEntity fee = findFeeInScopeOrThrow(feeId, organizationId, tournamentId);

        // 支払い者＝対象チームの代表（ADMIN/DEPUTY_ADMIN）であること
        requireTeamRepresentative(userId, teamId);
        // SPECIFIC_TEAMS のときは対象チームであること
        requireTeamIsTarget(fee, teamId);

        // F08.2 の既存 checkout フローへ委譲（Stripe Customer 解決・PENDING 作成・重複チェック等）
        return memberPaymentService.createCheckout(fee.getPaymentItemId(), userId);
    }

    // ========================================================================
    // 未払いゲート判定（領域⑥ 提出受理・エントリー確定 から呼ぶ）
    // ========================================================================

    /**
     * 指定チームが当該参加費を支払い済みかを判定する（未払いゲート用・設計書 §3.3）。
     *
     * <p>「チームが支払い済み」＝当該チームの ADMIN/DEPUTY_ADMIN のいずれかが、連結 payment_item に対して
     * 有効な PAID レコード（F08.2 の grace_period / validUntil を考慮）を持つこと。
     * 提出受理（{@code tournament_submission_requirement.requires_payment}）・エントリー確定の
     * ゲート条件として領域⑥ から参照される。</p>
     *
     * <p><strong>内部呼び出し専用（IDOR 注意）:</strong> 本メソッドは {@code feeRepository.findById(feeId)} を
     * organization_id / tournament_id で絞らずに引く。これはゲート判定の呼び出し元（領域⑥ の提出受理・
     * エントリー確定処理）が既に大会・組織スコープを検証済みの文脈から呼ぶ前提だからである。
     * 万一このロジックを HTTP エンドポイント化する場合は、必ず呼び出し前に
     * {@link #findFeeInScopeOrThrow(UUID, Long, Long)} 等で fee の所属（org / tournament）を検証し、
     * 他組織・他大会の fee を本メソッドへ素通しさせないこと（IDOR 対策）。</p>
     *
     * @return 支払い済みなら true
     */
    public boolean isTeamPaid(UUID feeId, Long teamId) {
        // 内部専用: org/tournament で絞らない findById。EP 化時は呼び出し元でスコープ検証必須（上記 Javadoc 参照）。
        TournamentFeeEntity fee = feeRepository.findById(feeId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.FEE_NOT_FOUND));
        // SPECIFIC_TEAMS で対象外のチームは「課金対象でない」＝支払い不要としてゲートを通す（true）
        if (fee.getTargetScope() == TournamentFeeTargetScope.SPECIFIC_TEAMS
                && !feeTargetRepository.existsByFeeIdAndTeamId(feeId, teamId)) {
            return true;
        }
        return memberPaymentRepository.existsValidPaidPaymentByTeamRepresentative(teamId, fee.getPaymentItemId());
    }

    /**
     * 指定チームが当該大会／ディビジョンの全参加費を支払い済みかを判定する（未払いゲート用・設計書 §5）。
     *
     * <p>領域⑥ の提出受理（{@code tournament_submission_requirement.requires_payment=TRUE}）で
     * 「大会参加費の支払い済み」をゲート条件にするための内部呼び出し。大会／ディビジョンに紐づく
     * 参加費（{@code tournament_fee}）を列挙し、当該チームに適用される全ての参加費について
     * {@link #isTeamPaid(UUID, Long)} が true（SPECIFIC_TEAMS の対象外は支払い不要として true）の場合のみ
     * true を返す。参加費が 1 件も存在しない大会は「課金なし」＝true（ゲートを通す）。</p>
     *
     * <p>divisionId が指定された場合、大会全体（division_id IS NULL）の参加費に加え、
     * 当該ディビジョンに限定された参加費（division_id = divisionId）も対象とする。
     * 他ディビジョン限定の参加費は当該チームの提出には無関係なので除外する。</p>
     *
     * <p><strong>内部呼び出し専用（IDOR 注意）:</strong> 本メソッドは tournament_id で参加費を引く。
     * 呼び出し元（領域⑥ の提出受理）が既に大会・組織スコープを検証済みの文脈から呼ぶ前提である。</p>
     *
     * @param tournamentId 大会 ID
     * @param divisionId   提出枠のディビジョン ID（NULL = 大会全体の枠）
     * @param teamId       提出チーム ID
     * @return 適用される全参加費を支払い済みなら true
     */
    public boolean isTeamPaidForTournament(Long tournamentId, Long divisionId, Long teamId) {
        List<TournamentFeeEntity> fees = feeRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId);
        for (TournamentFeeEntity fee : fees) {
            // 他ディビジョン限定の参加費は当該チームの提出には無関係なので除外する。
            // 大会全体（fee.divisionId == null）は常に対象。
            if (fee.getDivisionId() != null && !fee.getDivisionId().equals(divisionId)) {
                continue;
            }
            if (!isTeamPaid(fee.getId(), teamId)) {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private TournamentFeeResponse toResponse(TournamentFeeEntity fee) {
        PaymentItemEntity item = paymentItemService.findByIdOrThrow(fee.getPaymentItemId());
        List<Long> targetTeamIds = fee.getTargetScope() == TournamentFeeTargetScope.SPECIFIC_TEAMS
                ? feeTargetRepository.findByFeeId(fee.getId()).stream()
                        .map(TournamentFeeTargetEntity::getTeamId).toList()
                : List.of();
        return TournamentFeeResponse.of(fee, targetTeamIds, item.getAmount(), item.getCurrency());
    }

    private List<Long> persistTargets(UUID feeId, TournamentFeeTargetScope scope, List<Long> teamIds) {
        if (scope != TournamentFeeTargetScope.SPECIFIC_TEAMS || teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = teamIds.stream().distinct().toList();
        for (Long teamId : distinct) {
            feeTargetRepository.save(TournamentFeeTargetEntity.builder()
                    .feeId(feeId)
                    .teamId(teamId)
                    .build());
        }
        return distinct;
    }

    private TournamentEntity findTournamentInOrgOrThrow(Long organizationId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        // 他組織の大会は存在を隠して 404（IDOR 対策）
        if (!tournament.getOrganizationId().equals(organizationId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    private void verifyDivisionBelongsToTournament(Long divisionId, Long tournamentId) {
        TournamentDivisionEntity division = divisionRepository.findById(divisionId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
        if (!division.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
    }

    private PaymentItemEntity requireOrganizationPaymentItem(Long paymentItemId, Long organizationId) {
        PaymentItemEntity item = paymentItemService.findByIdOrThrow(paymentItemId);
        if (!organizationId.equals(item.getOrganizationId())) {
            throw new BusinessException(TournamentErrorCode.FEE_PAYMENT_ITEM_SCOPE_MISMATCH);
        }
        return item;
    }

    private TournamentFeeEntity findFeeInScopeOrThrow(UUID feeId, Long organizationId, Long tournamentId) {
        TournamentFeeEntity fee = feeRepository.findByIdAndOrganizationId(feeId, organizationId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.FEE_NOT_FOUND));
        if (!fee.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.FEE_NOT_FOUND);
        }
        return fee;
    }

    private void requireOrganizerAdmin(Long userId, Long organizationId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.FEE_MANAGE_FORBIDDEN);
    }

    private void requireTeamRepresentative(Long userId, Long teamId) {
        if (userId == null || !accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TournamentErrorCode.FEE_PAY_FORBIDDEN);
        }
    }

    private void requireTeamIsTarget(TournamentFeeEntity fee, Long teamId) {
        if (fee.getTargetScope() == TournamentFeeTargetScope.SPECIFIC_TEAMS
                && !feeTargetRepository.existsByFeeIdAndTeamId(fee.getId(), teamId)) {
            throw new BusinessException(TournamentErrorCode.FEE_TEAM_NOT_TARGET);
        }
    }
}
