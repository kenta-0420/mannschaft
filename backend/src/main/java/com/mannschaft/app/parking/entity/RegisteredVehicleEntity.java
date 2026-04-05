package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 登録車両エンティティ。ナンバープレートは暗号化して保存する。
 */
@Entity
@Table(name = "registered_vehicles")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RegisteredVehicleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private byte[] plateNumber;

    @Column(nullable = false, length = 64)
    private String plateNumberHash;

    @Column(length = 50)
    private String nickname;

    private LocalDateTime deletedAt;

    /**
     * 車両情報を更新する。
     */
    public void update(VehicleType vehicleType, byte[] plateNumber,
                       String plateNumberHash, String nickname) {
        this.vehicleType = vehicleType;
        this.plateNumber = plateNumber;
        this.plateNumberHash = plateNumberHash;
        this.nickname = nickname;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
