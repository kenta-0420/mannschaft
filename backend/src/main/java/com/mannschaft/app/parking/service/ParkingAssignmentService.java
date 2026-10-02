package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceStatus;
import com.mannschaft.app.parking.dto.AssignRequest;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.dto.BulkAssignRequest;
import com.mannschaft.app.parking.dto.ReleaseRequest;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 区画割り当てサービス。割り当て・解除・一括割り当てを担当する。
 *
 * <p>認可根治戦役 Wave6: 本サービスの操作系（{@link #assign}/{@link #release}/{@link #bulkAssign}）は
 * {@code currentUserId} を受け取りながら <b>{@code assignedBy}（監査欄）に記録するだけで認可判定に
 * 使っていなかった</b>ため、スコープに所属しない任意のログインユーザーが他人の駐車割り当てを
 * 作成・解除できる認可欠落（BOLA）が生じていた。兄弟の {@link ParkingSpaceService}（Wave2 トランシェ2B）が
 * 既に敷設済みのパターンをそのまま踏襲して根治する。</p>
 *
 * <h3>敷設パターン（{@link ParkingSpaceService} と同一）</h3>
 * <ul>
 *   <li>操作系（割り当て・解除・一括割り当て）は変更系のため {@code checkAdminOrAbove}（非 ADMIN は 403 COMMON_002）。</li>
 *   <li>対象 ID 指定操作（assign/release）は<b>先に対象区画を fetch</b> し、
 *       <b>entity 由来の scopeType/scopeId</b> で認可する（path 由来 scopeId の鵜呑みを避け BOLA を防止）。
 *       スコープ外の区画 ID は fetch 段階で 404（{@code PARKING_001}）となり存在秘匿される。</li>
 *   <li>スコープ宣言型操作（bulkAssign）は path 由来 scope で入口の先頭に敷く。</li>
 * </ul>
 *
 * <p>認可は各 public 入口メソッドに置く（{@code feedback_authz_gate_on_public_entry_not_shared_method}）。
 * {@link #bulkAssign} は内部で {@link #assign} を呼ぶため各明細で再検証されるが、
 * 同一スコープに対する冪等な再確認であり正当な操作を妨げない（多層防御として温存する）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingAssignmentService {

    private final ParkingSpaceRepository spaceRepository;
    private final ParkingAssignmentRepository assignmentRepository;
    private final ParkingSettingsRepository settingsRepository;
    private final ParkingMapper parkingMapper;
    private final AccessControlService accessControlService;

    /**
     * 区画を割り当てる。
     */
    @Transactional
    public AssignmentResponse assign(String scopeType, Long scopeId, Long spaceId,
                                      AssignRequest request, Long currentUserId) {
        ParkingSpaceEntity space = spaceRepository.findByIdAndScopeTypeAndScopeId(spaceId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));
        // 変更系のため ADMIN 以上を要求する。entity 由来 scope で検証し BOLA を防ぐ（Wave6）
        accessControlService.checkAdminOrAbove(currentUserId, space.getScopeId(), space.getScopeType());

        if (space.getStatus() != SpaceStatus.VACANT) {
            throw new BusinessException(ParkingErrorCode.SPACE_NOT_VACANT);
        }

        // 最大割り当て数チェック
        checkMaxSpaces(scopeType, scopeId, request.getUserId());

        ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                .spaceId(spaceId)
                .userId(request.getUserId())
                .vehicleId(request.getVehicleId())
                .assignedBy(currentUserId)
                .contractStartDate(request.getContractStartDate())
                .contractEndDate(request.getContractEndDate())
                .build();
        ParkingAssignmentEntity saved = assignmentRepository.save(entity);

        space.changeStatus(SpaceStatus.OCCUPIED);
        space.resetApplicationStatus();
        spaceRepository.save(space);

        log.info("区画割り当て: spaceId={}, userId={}", spaceId, request.getUserId());
        return parkingMapper.toAssignmentResponse(saved);
    }

    /**
     * 区画の割り当てを解除する。
     */
    @Transactional
    public void release(String scopeType, Long scopeId, Long spaceId,
                        ReleaseRequest request, Long currentUserId) {
        ParkingSpaceEntity space = spaceRepository.findByIdAndScopeTypeAndScopeId(spaceId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));
        // 変更系のため ADMIN 以上を要求する。entity 由来 scope で検証し BOLA を防ぐ（Wave6）
        accessControlService.checkAdminOrAbove(currentUserId, space.getScopeId(), space.getScopeType());

        ParkingAssignmentEntity assignment = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(spaceId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.ASSIGNMENT_NOT_FOUND));

        assignment.release(currentUserId, request.getReleaseReason());
        assignmentRepository.save(assignment);

        space.changeStatus(SpaceStatus.VACANT);
        spaceRepository.save(space);

        log.info("区画解除: spaceId={}", spaceId);
    }

    /**
     * 一括割り当てを実行する。
     */
    @Transactional
    public List<AssignmentResponse> bulkAssign(String scopeType, Long scopeId,
                                                BulkAssignRequest request, Long currentUserId) {
        // スコープ宣言型の変更系入口。明細を1件も処理する前に ADMIN 以上を要求する（Wave6）
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        if (request.getAssignments().size() > 50) {
            throw new BusinessException(ParkingErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<AssignmentResponse> results = new ArrayList<>();
        for (BulkAssignRequest.BulkAssignItem item : request.getAssignments()) {
            AssignRequest assignRequest = new AssignRequest(item.getUserId(), item.getVehicleId(),
                    item.getContractStartDate(), item.getContractEndDate());
            results.add(assign(scopeType, scopeId, item.getSpaceId(), assignRequest, currentUserId));
        }
        log.info("一括割り当て: scopeType={}, scopeId={}, count={}", scopeType, scopeId, results.size());
        return results;
    }

    private void checkMaxSpaces(String scopeType, Long scopeId, Long userId) {
        ParkingSettingsEntity settings = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);
        int maxSpaces = settings != null ? settings.getMaxSpacesPerUser() : 1;
        long currentCount = assignmentRepository.countByUserIdAndReleasedAtIsNull(userId);
        if (currentCount >= maxSpaces) {
            throw new BusinessException(ParkingErrorCode.MAX_SPACES_EXCEEDED);
        }
    }
}
