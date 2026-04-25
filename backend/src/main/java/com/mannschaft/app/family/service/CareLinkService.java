package com.mannschaft.app.family.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareRelationship;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.dto.CareLinkNotifySettingsRequest;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.dto.InviteRecipientRequest;
import com.mannschaft.app.family.dto.InviteWatcherRequest;
import com.mannschaft.app.family.dto.TeamCareOverrideRequest;
import com.mannschaft.app.family.dto.TeamCareOverrideResponse;
import com.mannschaft.app.family.entity.TeamCareNotificationOverrideEntity;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.TeamCareNotificationOverrideRepository;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ケアリンクサービス。F03.12 ケア対象者イベント参加見守り通知システム。
 *
 * <p>ケア対象者と見守り者の関係（ケアリンク）の CRUD、招待フロー、
 * 通知設定管理、チーム別上書き設定を提供する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareLinkService {

    /** 1人のケア対象者に紐付けられる最大ケアリンク数。 */
    private static final int MAX_CARE_LINKS_PER_RECIPIENT = 5;

    private final UserCareLinkRepository careLinkRepository;
    private final TeamCareNotificationOverrideRepository overrideRepository;

    // =========================================================
    // 招待・承認フロー
    // =========================================================

    /**
     * ケア対象者が見守り者を招待する。
     *
     * @param careRecipientUserId ケア対象者のユーザーID
     * @param request             招待リクエスト
     * @return 作成されたケアリンク
     */
    @Transactional
    public CareLinkResponse inviteWatcher(Long careRecipientUserId, InviteWatcherRequest request) {
        // 自己参照チェック
        if (careRecipientUserId.equals(request.getWatcherUserId())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_026);
        }
        // 上限チェック
        long activeCount = careLinkRepository.countByCareRecipientUserIdAndStatusIn(
                careRecipientUserId, List.of(CareLinkStatus.PENDING, CareLinkStatus.ACTIVE));
        if (activeCount >= MAX_CARE_LINKS_PER_RECIPIENT) {
            throw new BusinessException(FamilyErrorCode.FAMILY_027);
        }
        // 重複チェック
        if (careLinkRepository.existsByCareRecipientUserIdAndWatcherUserId(
                careRecipientUserId, request.getWatcherUserId())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_028);
        }

        UserCareLinkEntity link = UserCareLinkEntity.builder()
                .careRecipientUserId(careRecipientUserId)
                .watcherUserId(request.getWatcherUserId())
                .careCategory(request.getCareCategory())
                .relationship(request.getRelationship() != null
                        ? request.getRelationship()
                        : CareRelationship.PARENT)
                .isPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : true)
                .status(CareLinkStatus.PENDING)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .invitationToken(generateToken())
                .invitationSentAt(LocalDateTime.now())
                .createdBy(careRecipientUserId)
                .build();

        return toResponse(careLinkRepository.save(link));
    }

    /**
     * 見守り者がケア対象者を招待する。
     *
     * @param watcherUserId ログイン中の見守り者ユーザーID
     * @param request       招待リクエスト
     * @return 作成されたケアリンク
     */
    @Transactional
    public CareLinkResponse inviteCareRecipient(Long watcherUserId, InviteRecipientRequest request) {
        if (watcherUserId.equals(request.getCareRecipientUserId())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_026);
        }
        long activeCount = careLinkRepository.countByCareRecipientUserIdAndStatusIn(
                request.getCareRecipientUserId(), List.of(CareLinkStatus.PENDING, CareLinkStatus.ACTIVE));
        if (activeCount >= MAX_CARE_LINKS_PER_RECIPIENT) {
            throw new BusinessException(FamilyErrorCode.FAMILY_027);
        }
        if (careLinkRepository.existsByCareRecipientUserIdAndWatcherUserId(
                request.getCareRecipientUserId(), watcherUserId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_028);
        }

        UserCareLinkEntity link = UserCareLinkEntity.builder()
                .careRecipientUserId(request.getCareRecipientUserId())
                .watcherUserId(watcherUserId)
                .careCategory(request.getCareCategory())
                .relationship(request.getRelationship() != null
                        ? request.getRelationship()
                        : CareRelationship.PARENT)
                .isPrimary(false)
                .status(CareLinkStatus.PENDING)
                .invitedBy(CareLinkInvitedBy.WATCHER)
                .invitationToken(generateToken())
                .invitationSentAt(LocalDateTime.now())
                .createdBy(watcherUserId)
                .build();

        return toResponse(careLinkRepository.save(link));
    }

    /**
     * 招待トークンを使って招待を承認する。
     *
     * @param token         招待トークン
     * @param currentUserId 承認するユーザーのID（将来的な本人確認用）
     * @return 更新されたケアリンク
     */
    @Transactional
    public CareLinkResponse acceptInvitation(String token, Long currentUserId) {
        UserCareLinkEntity link = careLinkRepository.findByInvitationToken(token)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_029));
        if (link.getStatus() != CareLinkStatus.PENDING) {
            throw new BusinessException(FamilyErrorCode.FAMILY_031);
        }
        link.activate(LocalDateTime.now());
        // キャッシュ手動 eviction（@CacheEvict は SpEL で link.getCareRecipientUserId() を参照できないため）
        evictCareLinkCaches(link.getCareRecipientUserId());
        return toResponse(link);
    }

    /**
     * 招待トークンを使って招待を拒否する。
     *
     * @param token         招待トークン
     * @param currentUserId 拒否するユーザーのID（将来的な本人確認用）
     */
    @Transactional
    public void rejectInvitation(String token, Long currentUserId) {
        UserCareLinkEntity link = careLinkRepository.findByInvitationToken(token)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_029));
        link.reject();
    }

    /**
     * ケアリンクを解除する。
     *
     * @param linkId        解除するケアリンクID
     * @param currentUserId 解除操作を行うユーザーID（当事者チェック）
     */
    @Transactional
    public void revokeLink(Long linkId, Long currentUserId) {
        UserCareLinkEntity link = careLinkRepository.findById(linkId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_025));
        // 当事者チェック（ケア対象者または見守り者のみ）
        if (!link.getCareRecipientUserId().equals(currentUserId)
                && !link.getWatcherUserId().equals(currentUserId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_030);
        }
        link.revoke(currentUserId);
        evictCareLinkCaches(link.getCareRecipientUserId());
    }

    /**
     * 招待トークンからケアリンク情報を取得する（確認画面表示用）。
     *
     * @param token 招待トークン
     * @return 対応するケアリンク情報
     */
    public CareLinkResponse getByInvitationToken(String token) {
        UserCareLinkEntity link = careLinkRepository.findByInvitationToken(token)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_029));
        return toResponse(link);
    }

    // =========================================================
    // 一覧・状態照会
    // =========================================================

    /**
     * 自分がケア対象者として登録されているアクティブなケアリンク一覧を取得する。
     */
    public List<CareLinkResponse> getActiveLinksForCareRecipient(Long recipientUserId) {
        return careLinkRepository.findByCareRecipientUserIdAndStatus(recipientUserId, CareLinkStatus.ACTIVE)
                .stream().map(this::toResponse).toList();
    }

    /**
     * 自分が見守り者として登録されているアクティブなケアリンク一覧を取得する。
     */
    public List<CareLinkResponse> getActiveLinksForWatcher(Long watcherUserId) {
        return careLinkRepository.findByWatcherUserIdAndStatus(watcherUserId, CareLinkStatus.ACTIVE)
                .stream().map(this::toResponse).toList();
    }

    /**
     * ユーザーに届いている（または発行した）保留中招待一覧を取得する。
     */
    public List<CareLinkResponse> getPendingInvitationsForUser(Long userId) {
        return careLinkRepository.findPendingInvitationsForUser(userId)
                .stream().map(this::toResponse).toList();
    }

    /**
     * ケアリンクの通知設定を更新する。
     *
     * @param linkId        対象ケアリンクID
     * @param currentUserId 操作するユーザーID（当事者チェック）
     * @param request       更新内容
     * @return 更新されたケアリンク
     */
    @Transactional
    public CareLinkResponse updateNotifySettings(Long linkId, Long currentUserId,
                                                  CareLinkNotifySettingsRequest request) {
        UserCareLinkEntity link = careLinkRepository.findById(linkId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_025));
        if (!link.getCareRecipientUserId().equals(currentUserId)
                && !link.getWatcherUserId().equals(currentUserId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_030);
        }
        link.updateNotifySettings(
                request.getNotifyOnRsvp(),
                request.getNotifyOnCheckin(),
                request.getNotifyOnCheckout(),
                request.getNotifyOnAbsentAlert(),
                request.getNotifyOnDismissal());
        return toResponse(link);
    }

    // =========================================================
    // チーム別上書き設定
    // =========================================================

    /**
     * チームのケア通知上書き設定を取得する。設定がない場合は null を返す。
     */
    public TeamCareOverrideResponse getTeamOverride(String scopeType, Long scopeId, Long careLinkId) {
        return overrideRepository
                .findByScopeTypeAndScopeIdAndCareLinkId(scopeType, scopeId, careLinkId)
                .map(this::toOverrideResponse)
                .orElse(null);
    }

    /**
     * チームのケア通知上書き設定を作成または更新する（upsert）。
     */
    @Transactional
    public TeamCareOverrideResponse upsertTeamOverride(String scopeType, Long scopeId, Long careLinkId,
                                                        Long currentUserId, TeamCareOverrideRequest request) {
        TeamCareNotificationOverrideEntity entity = overrideRepository
                .findByScopeTypeAndScopeIdAndCareLinkId(scopeType, scopeId, careLinkId)
                .orElse(TeamCareNotificationOverrideEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .careLinkId(careLinkId)
                        .createdBy(currentUserId)
                        .build());
        entity.updateSettings(
                request.getNotifyOnRsvp(),
                request.getNotifyOnCheckin(),
                request.getNotifyOnCheckout(),
                request.getNotifyOnAbsentAlert(),
                request.getNotifyOnDismissal(),
                request.getDisabled());
        return toOverrideResponse(overrideRepository.save(entity));
    }

    /**
     * チームのケア通知上書き設定を削除する。
     */
    @Transactional
    public void deleteTeamOverride(String scopeType, Long scopeId, Long careLinkId) {
        overrideRepository
                .findByScopeTypeAndScopeIdAndCareLinkId(scopeType, scopeId, careLinkId)
                .ifPresent(overrideRepository::delete);
    }

    // =========================================================
    // ケア対象者判定（Phase3バッチ・通知サービスから利用）
    // =========================================================

    /**
     * 指定ユーザーがケア対象者かどうかを判定する。
     *
     * <p>ACTIVE なケアリンクが1件以上存在する場合に true を返す。5分キャッシュ。</p>
     *
     * @param userId 判定対象ユーザーID
     * @return ケア対象者の場合 true
     */
    @Cacheable(value = "careLinks", key = "#userId")
    public boolean isUnderCare(Long userId) {
        return careLinkRepository.existsByCareRecipientUserIdAndStatus(userId, CareLinkStatus.ACTIVE);
    }

    /**
     * 通知種別に応じたアクティブな見守り者ユーザーIDリストを取得する。
     *
     * <p>5分キャッシュ。Phase3 のバッチ通知処理から呼び出される。</p>
     *
     * @param recipientUserId ケア対象者のユーザーID
     * @param notifyType      通知種別文字列（RSVP / CHECKIN / CHECKOUT / ABSENT_ALERT / DISMISSAL）
     * @return 通知対象の見守り者ユーザーIDリスト
     */
    @Cacheable(value = "careCategory", key = "#recipientUserId + ':' + #notifyType")
    public List<Long> getActiveWatchers(Long recipientUserId, String notifyType) {
        List<UserCareLinkEntity> links = careLinkRepository
                .findByCareRecipientUserIdAndStatus(recipientUserId, CareLinkStatus.ACTIVE);
        return links.stream()
                .filter(link -> matchesNotifyType(link, notifyType))
                .map(UserCareLinkEntity::getWatcherUserId)
                .toList();
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 指定リンクが通知種別の条件を満たすか判定する。
     */
    private boolean matchesNotifyType(UserCareLinkEntity link, String notifyType) {
        return switch (notifyType) {
            case "RSVP"         -> Boolean.TRUE.equals(link.getNotifyOnRsvp());
            case "CHECKIN"      -> Boolean.TRUE.equals(link.getNotifyOnCheckin());
            case "CHECKOUT"     -> Boolean.TRUE.equals(link.getNotifyOnCheckout());
            case "ABSENT_ALERT" -> Boolean.TRUE.equals(link.getNotifyOnAbsentAlert());
            case "DISMISSAL"    -> Boolean.TRUE.equals(link.getNotifyOnDismissal());
            default -> true;
        };
    }

    /** UUID ベースの招待トークンを生成する。 */
    private String generateToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * careLinks / careCategory キャッシュを手動 evict する。
     * revokeLink / acceptInvitation の後に呼び出す。
     */
    @CacheEvict(value = {"careLinks", "careCategory"}, allEntries = true)
    public void evictCareLinkCaches(Long careRecipientUserId) {
        // キャッシュ eviction のみ。ロジック本体は呼び出し元メソッドに実装されている。
        log.debug("careLinks キャッシュを evict: recipientUserId={}", careRecipientUserId);
    }

    private CareLinkResponse toResponse(UserCareLinkEntity e) {
        return CareLinkResponse.builder()
                .id(e.getId())
                .careRecipientUserId(e.getCareRecipientUserId())
                .watcherUserId(e.getWatcherUserId())
                .careCategory(e.getCareCategory())
                .relationship(e.getRelationship())
                .isPrimary(e.getIsPrimary())
                .status(e.getStatus())
                .invitedBy(e.getInvitedBy())
                .confirmedAt(e.getConfirmedAt())
                .notifyOnRsvp(e.getNotifyOnRsvp())
                .notifyOnCheckin(e.getNotifyOnCheckin())
                .notifyOnCheckout(e.getNotifyOnCheckout())
                .notifyOnAbsentAlert(e.getNotifyOnAbsentAlert())
                .notifyOnDismissal(e.getNotifyOnDismissal())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private TeamCareOverrideResponse toOverrideResponse(TeamCareNotificationOverrideEntity e) {
        return TeamCareOverrideResponse.builder()
                .id(e.getId())
                .scopeType(e.getScopeType())
                .scopeId(e.getScopeId())
                .careLinkId(e.getCareLinkId())
                .notifyOnRsvp(e.getNotifyOnRsvp())
                .notifyOnCheckin(e.getNotifyOnCheckin())
                .notifyOnCheckout(e.getNotifyOnCheckout())
                .notifyOnAbsentAlert(e.getNotifyOnAbsentAlert())
                .notifyOnDismissal(e.getNotifyOnDismissal())
                .disabled(e.getDisabled())
                .build();
    }
}
