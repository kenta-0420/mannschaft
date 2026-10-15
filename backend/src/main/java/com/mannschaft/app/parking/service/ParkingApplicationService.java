package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.parking.ApplicationStatus;
import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateApplicationRequest;
import com.mannschaft.app.parking.dto.RejectApplicationRequest;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 区画申請サービス。申請・承認・拒否・抽選を担当する。
 *
 * <p>認可根治戦役 Wave2 トランシェ2B: approve/reject/cancel/executeLottery は
 * これまで {@code findById} でテナント串刺し取得していた（他スコープの申請を承認/拒否/キャンセル/抽選できるBOLA）。
 * 対象の {@link ParkingApplicationEntity#getSpaceId()} から区画を scope 込みで fetch し、
 * entity 由来の scopeType/scopeId で {@code checkAdminOrAbove} を敷設した。
 * cancel のみ申請者本人によるセルフキャンセルを許容する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingApplicationService {

    private final ParkingApplicationRepository applicationRepository;
    private final ParkingSpaceRepository spaceRepository;
    private final ParkingMapper parkingMapper;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final AccessControlService accessControlService;

    /**
     * 申請一覧をページング取得する。
     */
    public Page<ApplicationResponse> list(List<Long> spaceIds, String status, Pageable pageable) {
        if (spaceIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<ParkingApplicationEntity> page;
        if (status != null) {
            page = applicationRepository.findBySpaceIdInAndStatus(spaceIds, ParkingApplicationStatus.valueOf(status), pageable);
        } else {
            page = applicationRepository.findBySpaceIdIn(spaceIds, pageable);
        }
        return page.map(parkingMapper::toApplicationResponse);
    }

    /**
     * 申請を作成する。
     */
    @Transactional
    public ApplicationResponse create(List<Long> spaceIds, Long userId, CreateApplicationRequest request) {
        if (!spaceIds.contains(request.getSpaceId())) {
            throw new BusinessException(ParkingErrorCode.SCOPE_MISMATCH);
        }

        ParkingSpaceEntity space = spaceRepository.findById(request.getSpaceId())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));

        if (space.getApplicationStatus() != ApplicationStatus.ACCEPTING) {
            throw new BusinessException(ParkingErrorCode.NOT_ACCEPTING_APPLICATIONS);
        }

        // 重複申請チェック
        applicationRepository.findBySpaceIdAndUserIdAndStatusIn(
                request.getSpaceId(), userId,
                List.of(ParkingApplicationStatus.PENDING, ParkingApplicationStatus.LOTTERY_PENDING)
        ).ifPresent(a -> {
            throw new BusinessException(ParkingErrorCode.APPLICATION_ALREADY_EXISTS);
        });

        ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                .spaceId(request.getSpaceId())
                .userId(userId)
                .vehicleId(request.getVehicleId())
                .message(request.getMessage())
                .build();
        ParkingApplicationEntity saved = applicationRepository.save(entity);

        // 代理入力の場合: proxy_input_records を作成し、フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveProxyInputRecord(
                    "PARKING_APPLICATION", saved.getId());
            saved = applicationRepository.save(saved.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.info("区画申請: spaceId={}, userId={}", request.getSpaceId(), userId);
        return parkingMapper.toApplicationResponse(saved);
    }

    /**
     * 申請を承認する。
     */
    @Transactional
    public ApplicationResponse approve(String scopeType, Long scopeId, Long id, Long actorUserId) {
        ParkingApplicationEntity entity = findScopeApplicationOrThrow(scopeType, scopeId, id);
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        if (entity.getStatus() != ParkingApplicationStatus.PENDING && entity.getStatus() != ParkingApplicationStatus.LOTTERY_PENDING) {
            throw new BusinessException(ParkingErrorCode.INVALID_APPLICATION_STATUS);
        }
        entity.approve();
        ParkingApplicationEntity saved = applicationRepository.save(entity);
        log.info("申請承認: id={}", id);
        return parkingMapper.toApplicationResponse(saved);
    }

    /**
     * 申請を拒否する。
     */
    @Transactional
    public ApplicationResponse reject(String scopeType, Long scopeId, Long id, RejectApplicationRequest request, Long actorUserId) {
        ParkingApplicationEntity entity = findScopeApplicationOrThrow(scopeType, scopeId, id);
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        if (entity.getStatus() != ParkingApplicationStatus.PENDING && entity.getStatus() != ParkingApplicationStatus.LOTTERY_PENDING) {
            throw new BusinessException(ParkingErrorCode.INVALID_APPLICATION_STATUS);
        }
        entity.reject(request.getRejectionReason());
        ParkingApplicationEntity saved = applicationRepository.save(entity);
        log.info("申請拒否: id={}", id);
        return parkingMapper.toApplicationResponse(saved);
    }

    /**
     * 申請をキャンセルする（申請者本人 または ADMIN 以上）。
     */
    @Transactional
    public void cancel(String scopeType, Long scopeId, Long id, Long actorUserId) {
        ParkingApplicationEntity entity = findScopeApplicationOrThrow(scopeType, scopeId, id);
        if (!entity.getUserId().equals(actorUserId)) {
            accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        }
        if (entity.getStatus() != ParkingApplicationStatus.PENDING && entity.getStatus() != ParkingApplicationStatus.LOTTERY_PENDING) {
            throw new BusinessException(ParkingErrorCode.INVALID_APPLICATION_STATUS);
        }
        entity.cancel();
        applicationRepository.save(entity);
        log.info("申請キャンセル: id={}", id);
    }

    /**
     * 抽選を実行する。
     */
    @Transactional
    public List<ApplicationResponse> executeLottery(String scopeType, Long scopeId, Long spaceId, Long actorUserId) {
        ParkingSpaceEntity space = spaceRepository.findByIdAndScopeTypeAndScopeId(spaceId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));
        accessControlService.checkAdminOrAbove(actorUserId, space.getScopeId(), space.getScopeType());

        List<ParkingApplicationEntity> candidates = applicationRepository.findBySpaceIdAndStatus(spaceId, ParkingApplicationStatus.PENDING);
        if (candidates.isEmpty()) {
            throw new BusinessException(ParkingErrorCode.NO_LOTTERY_CANDIDATES);
        }

        // シャッフルして番号付与
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).markLotteryPending(i + 1);
        }
        applicationRepository.saveAll(candidates);

        // 区画の申請受付を締切
        space.closeLottery();
        spaceRepository.save(space);

        log.info("抽選実行: spaceId={}, candidates={}", spaceId, candidates.size());
        return parkingMapper.toApplicationResponseList(candidates);
    }

    /**
     * 対象申請を scope 込みで取得する（他スコープの申請へのBOLA防止）。
     *
     * <p>申請自体は scopeType/scopeId を保持しないため、紐づく区画を経由して
     * 対象スコープに属するかを検証する。区画がスコープ外、または申請自体が
     * 見つからない場合は同一の {@code APPLICATION_NOT_FOUND} を返し存在秘匿する。</p>
     */
    private ParkingApplicationEntity findScopeApplicationOrThrow(String scopeType, Long scopeId, Long id) {
        ParkingApplicationEntity entity = applicationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.APPLICATION_NOT_FOUND));
        spaceRepository.findByIdAndScopeTypeAndScopeId(entity.getSpaceId(), scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.APPLICATION_NOT_FOUND));
        return entity;
    }

    /**
     * 代理入力記録を作成して保存する（冪等性チェックあり）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済み代理入力記録エンティティ
     */
    private ProxyInputRecordEntity buildAndSaveProxyInputRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("PARKING_APPLICATION")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }
}
