package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminErrorCode;
import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.MaintenanceMode;
import com.mannschaft.app.admin.MaintenanceStatus;
import com.mannschaft.app.admin.dto.CreateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.UpdateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.admin.repository.MaintenanceScheduleRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * メンテナンススケジュールサービス。メンテナンスの登録・更新・削除・手動開始を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceScheduleService {

    private final MaintenanceScheduleRepository repository;
    private final AdminMapper adminMapper;

    /**
     * メンテナンススケジュール一覧を取得する。
     *
     * @return スケジュール一覧
     */
    public List<MaintenanceScheduleResponse> getAllSchedules() {
        List<MaintenanceScheduleEntity> entities = repository.findByStatusInOrderByStartsAtDesc(
                List.of(MaintenanceStatus.SCHEDULED, MaintenanceStatus.ACTIVE, MaintenanceStatus.COMPLETED));
        return adminMapper.toMaintenanceScheduleResponseList(entities);
    }

    /**
     * メンテナンススケジュール詳細を取得する。
     *
     * @param id スケジュールID
     * @return スケジュール詳細
     */
    public MaintenanceScheduleResponse getSchedule(Long id) {
        MaintenanceScheduleEntity entity = findOrThrow(id);
        return adminMapper.toMaintenanceScheduleResponse(entity);
    }

    /**
     * メンテナンススケジュールを作成する。
     *
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたスケジュール
     */
    @Transactional
    public MaintenanceScheduleResponse createSchedule(CreateMaintenanceScheduleRequest req, Long userId) {
        validatePeriod(req.getStartsAt(), req.getEndsAt());

        MaintenanceMode mode = req.getMode() != null
                ? MaintenanceMode.valueOf(req.getMode())
                : MaintenanceMode.MAINTENANCE;

        MaintenanceScheduleEntity entity = MaintenanceScheduleEntity.builder()
                .title(req.getTitle())
                .message(req.getMessage())
                .mode(mode)
                .startsAt(req.getStartsAt())
                .endsAt(req.getEndsAt())
                .createdBy(userId)
                .build();
        entity = repository.save(entity);

        log.info("メンテナンススケジュール作成: id={}, title={}", entity.getId(), req.getTitle());
        return adminMapper.toMaintenanceScheduleResponse(entity);
    }

    /**
     * メンテナンススケジュールを更新する。
     *
     * @param id  スケジュールID
     * @param req 更新リクエスト
     * @return 更新後のスケジュール
     */
    @Transactional
    public MaintenanceScheduleResponse updateSchedule(Long id, UpdateMaintenanceScheduleRequest req) {
        MaintenanceScheduleEntity entity = findOrThrow(id);

        if (entity.getStatus() != MaintenanceStatus.SCHEDULED) {
            throw new BusinessException(AdminErrorCode.INVALID_MAINTENANCE_STATUS);
        }

        validatePeriod(req.getStartsAt(), req.getEndsAt());

        MaintenanceMode mode = req.getMode() != null
                ? MaintenanceMode.valueOf(req.getMode())
                : entity.getMode();

        entity.update(req.getTitle(), req.getMessage(), mode, req.getStartsAt(), req.getEndsAt());
        entity = repository.save(entity);

        log.info("メンテナンススケジュール更新: id={}", id);
        return adminMapper.toMaintenanceScheduleResponse(entity);
    }

    /**
     * メンテナンススケジュールを削除する（キャンセル）。
     *
     * @param id スケジュールID
     */
    @Transactional
    public void deleteSchedule(Long id) {
        MaintenanceScheduleEntity entity = findOrThrow(id);

        if (entity.getStatus() == MaintenanceStatus.ACTIVE) {
            throw new BusinessException(AdminErrorCode.INVALID_MAINTENANCE_STATUS);
        }

        entity.changeStatus(MaintenanceStatus.CANCELLED);
        repository.save(entity);
        log.info("メンテナンススケジュールキャンセル: id={}", id);
    }

    /**
     * メンテナンスを手動で開始する。
     *
     * @param id スケジュールID
     * @return 更新後のスケジュール
     */
    @Transactional
    public MaintenanceScheduleResponse activate(Long id) {
        MaintenanceScheduleEntity entity = findOrThrow(id);

        if (entity.getStatus() != MaintenanceStatus.SCHEDULED) {
            throw new BusinessException(AdminErrorCode.INVALID_MAINTENANCE_STATUS);
        }

        entity.changeStatus(MaintenanceStatus.ACTIVE);
        entity = repository.save(entity);

        log.info("メンテナンス手動開始: id={}", id);
        return adminMapper.toMaintenanceScheduleResponse(entity);
    }

    /**
     * メンテナンスを完了にする。
     *
     * @param id スケジュールID
     * @return 更新後のスケジュール
     */
    @Transactional
    public MaintenanceScheduleResponse complete(Long id) {
        MaintenanceScheduleEntity entity = findOrThrow(id);

        if (entity.getStatus() != MaintenanceStatus.ACTIVE) {
            throw new BusinessException(AdminErrorCode.INVALID_MAINTENANCE_STATUS);
        }

        entity.changeStatus(MaintenanceStatus.COMPLETED);
        entity = repository.save(entity);

        log.info("メンテナンス完了: id={}", id);
        return adminMapper.toMaintenanceScheduleResponse(entity);
    }

    // ---- private helpers ----

    private MaintenanceScheduleEntity findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.MAINTENANCE_NOT_FOUND));
    }

    private void validatePeriod(java.time.LocalDateTime startsAt, java.time.LocalDateTime endsAt) {
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new BusinessException(AdminErrorCode.INVALID_MAINTENANCE_PERIOD);
        }
    }
}
