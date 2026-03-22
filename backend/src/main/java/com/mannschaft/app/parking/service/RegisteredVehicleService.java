package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.VehicleType;
import com.mannschaft.app.parking.dto.CreateVehicleRequest;
import com.mannschaft.app.parking.dto.UpdateVehicleRequest;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.entity.RegisteredVehicleEntity;
import com.mannschaft.app.parking.repository.RegisteredVehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登録車両サービス。車両のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegisteredVehicleService {

    private final RegisteredVehicleRepository vehicleRepository;
    private final ParkingMapper parkingMapper;
    private final EncryptionService encryptionService;

    /**
     * ユーザーの車両一覧を取得する。
     */
    public List<VehicleResponse> listByUser(Long userId) {
        List<RegisteredVehicleEntity> vehicles = vehicleRepository.findByUserId(userId);
        return parkingMapper.toVehicleResponseList(vehicles);
    }

    /**
     * 車両を登録する。
     */
    @Transactional
    public VehicleResponse create(Long userId, CreateVehicleRequest request) {
        String hash = hashPlateNumber(request.getPlateNumber());
        vehicleRepository.findByPlateNumberHash(hash).ifPresent(v -> {
            throw new BusinessException(ParkingErrorCode.PLATE_NUMBER_DUPLICATE);
        });

        RegisteredVehicleEntity entity = RegisteredVehicleEntity.builder()
                .userId(userId)
                .vehicleType(VehicleType.valueOf(request.getVehicleType()))
                .plateNumber(encryptionService.encryptBytes(
                        request.getPlateNumber().getBytes(StandardCharsets.UTF_8)))
                .plateNumberHash(hash)
                .nickname(request.getNickname())
                .build();
        RegisteredVehicleEntity saved = vehicleRepository.save(entity);
        log.info("車両登録: userId={}, vehicleType={}", userId, request.getVehicleType());
        return parkingMapper.toVehicleResponse(saved);
    }

    /**
     * 車両を更新する。
     */
    @Transactional
    public VehicleResponse update(Long userId, Long id, UpdateVehicleRequest request) {
        RegisteredVehicleEntity entity = vehicleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VEHICLE_NOT_FOUND));
        String hash = hashPlateNumber(request.getPlateNumber());
        vehicleRepository.findByPlateNumberHash(hash).ifPresent(v -> {
            if (!v.getId().equals(id)) {
                throw new BusinessException(ParkingErrorCode.PLATE_NUMBER_DUPLICATE);
            }
        });

        entity.update(VehicleType.valueOf(request.getVehicleType()),
                encryptionService.encryptBytes(
                        request.getPlateNumber().getBytes(StandardCharsets.UTF_8)),
                hash, request.getNickname());
        RegisteredVehicleEntity saved = vehicleRepository.save(entity);
        log.info("車両更新: id={}", id);
        return parkingMapper.toVehicleResponse(saved);
    }

    /**
     * 車両を論理削除する。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        RegisteredVehicleEntity entity = vehicleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VEHICLE_NOT_FOUND));
        entity.softDelete();
        vehicleRepository.save(entity);
        log.info("車両削除: id={}", id);
    }

    /**
     * ナンバープレートのHMACハッシュを生成する。
     */
    private String hashPlateNumber(String plateNumber) {
        return encryptionService.hmac(plateNumber);
    }
}
