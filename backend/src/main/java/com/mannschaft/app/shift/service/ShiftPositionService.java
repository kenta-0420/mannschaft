package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftPositionService {

    private final ShiftPositionRepository positionRepository;
    private final ShiftMapper shiftMapper;

    /**
     * チームのポジション一覧を取得する。
     *
     * @param teamId チームID
     * @return ポジション一覧
     */
    public List<ShiftPositionResponse> listPositions(Long teamId) {
        List<ShiftPositionEntity> entities = positionRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        return shiftMapper.toPositionResponseList(entities);
    }

    /**
     * ポジションを作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @return 作成されたポジション
     */
    @Transactional
    public ShiftPositionResponse createPosition(Long teamId, CreatePositionRequest req) {
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
     * @return 更新されたポジション
     */
    @Transactional
    public ShiftPositionResponse updatePosition(Long positionId, UpdatePositionRequest req) {
        ShiftPositionEntity entity = findPositionOrThrow(positionId);

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
     */
    @Transactional
    public void deletePosition(Long positionId) {
        ShiftPositionEntity entity = findPositionOrThrow(positionId);
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
}
