package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフトポジションサービス。チーム内のシフト役割の定義・管理を担当する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> 全 public メソッドが操作者 {@code userId} を受け取り、
 * per-scope 認可する。更新系は<b>ポジション実体由来の teamId</b> で判定し、
 * パス変数・クエリの scope 値を鵜呑みにしない（BOLA 封鎖）。</p>
 *
 * <ul>
 *   <li><b>参照</b>: 当該チームのメンバー（SUPPORTER 不可）</li>
 *   <li><b>作成・更新・削除</b>: ADMIN/DEPUTY_ADMIN 以上（SYSTEM_ADMIN 短絡）</li>
 * </ul>
 *
 * <p>認可失敗は {@code COMMON_002}（403）。同ドメインの {@code ShiftScheduleScopeContractIT} /
 * {@code ShiftSlotScopeContractIT} の規約に揃える。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftPositionService {

    private final ShiftPositionRepository positionRepository;
    private final AccessControlService accessControlService;
    private final ShiftMapper shiftMapper;

    /**
     * チームのポジション一覧を取得する。
     *
     * @param teamId チームID
     * @param userId 操作者ユーザーID
     * @return ポジション一覧
     * @throws BusinessException 当該チームのメンバーでない場合（COMMON_002 / 403）
     */
    public List<ShiftPositionResponse> listPositions(Long teamId, Long userId) {
        checkTeamMemberAccess(teamId, userId);
        List<ShiftPositionEntity> entities = positionRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        return shiftMapper.toPositionResponseList(entities);
    }

    /**
     * ポジションを作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 操作者ユーザーID
     * @return 作成されたポジション
     * @throws BusinessException 当該チームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    @Transactional
    public ShiftPositionResponse createPosition(Long teamId, CreatePositionRequest req, Long userId) {
        checkTeamAdminAccess(teamId, userId);

        // 重複チェック
        positionRepository.findByTeamIdAndName(teamId, req.getName())
                .ifPresent(existing -> {
                    throw new BusinessException(ShiftErrorCode.POSITION_NAME_DUPLICATE);
                });

        ShiftPositionEntity entity = ShiftPositionEntity.builder()
                .teamId(teamId)
                .name(req.getName())
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();

        entity = positionRepository.save(entity);
        log.info("シフトポジション作成: id={}, teamId={}, name={}", entity.getId(), teamId, entity.getName());
        return shiftMapper.toPositionResponse(entity);
    }

    /**
     * ポジションを更新する。
     *
     * @param positionId ポジションID
     * @param req        更新リクエスト
     * @param userId     操作者ユーザーID
     * @return 更新されたポジション
     * @throws BusinessException 当該ポジションが属するチームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    @Transactional
    public ShiftPositionResponse updatePosition(Long positionId, UpdatePositionRequest req, Long userId) {
        ShiftPositionEntity entity = findPositionOrThrow(positionId);
        checkTeamAdminAccess(entity.getTeamId(), userId);

        if (req.getName() != null) {
            // 名前変更時は重複チェック
            positionRepository.findByTeamIdAndName(entity.getTeamId(), req.getName())
                    .filter(existing -> !existing.getId().equals(positionId))
                    .ifPresent(existing -> {
                        throw new BusinessException(ShiftErrorCode.POSITION_NAME_DUPLICATE);
                    });
            entity.changeName(req.getName());
        }
        if (req.getDisplayOrder() != null) {
            entity.changeDisplayOrder(req.getDisplayOrder());
        }
        if (req.getIsActive() != null) {
            if (Boolean.TRUE.equals(req.getIsActive())) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }

        entity = positionRepository.save(entity);
        log.info("シフトポジション更新: id={}", positionId);
        return shiftMapper.toPositionResponse(entity);
    }

    /**
     * ポジションを削除する。
     *
     * @param positionId ポジションID
     * @param userId     操作者ユーザーID
     * @throws BusinessException 当該ポジションが属するチームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    @Transactional
    public void deletePosition(Long positionId, Long userId) {
        ShiftPositionEntity entity = findPositionOrThrow(positionId);
        checkTeamAdminAccess(entity.getTeamId(), userId);
        positionRepository.delete(entity);
        log.info("シフトポジション削除: id={}", positionId);
    }

    /**
     * ポジションを取得する。存在しない場合は例外をスローする。
     */
    private ShiftPositionEntity findPositionOrThrow(Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_POSITION_NOT_FOUND));
    }

    /**
     * 管理操作の per-scope 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkTeamAdminAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * 参照の per-scope 認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkTeamMemberAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")
                || accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
