package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.parking.dto.CreateVehicleRequest;
import com.mannschaft.app.parking.dto.UpdateVehicleRequest;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.service.RegisteredVehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link VehicleController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>4 EP（list/create/update/delete）はいずれも {@code RegisteredVehicleService} へ
 * 認証主体の {@code USER_ID} のみを渡し、他ユーザーの ID を受け取る経路がエンドポイントに
 * 存在しないことを固定する。
 * {@code VehicleController#list} / {@code VehicleController#create} /
 * {@code VehicleController#update} / {@code VehicleController#delete} の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleController 単体テスト")
class VehicleControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long VEHICLE_ID = 100L;

    @Mock
    private RegisteredVehicleService vehicleService;

    @InjectMocks
    private VehicleController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private VehicleResponse vehicleResponse() {
        return new VehicleResponse(VEHICLE_ID, USER_ID, "CAR", "品川300あ1234", "マイカー",
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("list: 認証主体自身の userId のみで一覧取得する（VehicleController#list）")
    void list_自己スコープ() {
        given(vehicleService.listByUser(USER_ID)).willReturn(List.of(vehicleResponse()));

        ApiResponse<List<VehicleResponse>> result = controller.list().getBody();

        assertThat(result).isNotNull();
        assertThat(result.getData()).hasSize(1);
        verify(vehicleService).listByUser(USER_ID);
    }

    @Test
    @DisplayName("create: 認証主体自身の userId を所有者として登録する（VehicleController#create）")
    void create_自己スコープ() {
        CreateVehicleRequest req = new CreateVehicleRequest("CAR", "品川300あ1234", "マイカー");
        given(vehicleService.create(USER_ID, req)).willReturn(vehicleResponse());

        assertThat(controller.create(req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(vehicleService).create(USER_ID, req);
    }

    @Test
    @DisplayName("update: findByIdAndUserId(id, userId=自身) の複合キーで更新する（VehicleController#update）")
    void update_自己スコープ() {
        UpdateVehicleRequest req = new UpdateVehicleRequest("CAR", "品川300あ1234", "マイカー2");
        given(vehicleService.update(USER_ID, VEHICLE_ID, req)).willReturn(vehicleResponse());

        assertThat(controller.update(VEHICLE_ID, req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(vehicleService).update(USER_ID, VEHICLE_ID, req);
    }

    @Test
    @DisplayName("delete: findByIdAndUserId(id, userId=自身) の複合キーで削除する（VehicleController#delete）")
    void delete_自己スコープ() {
        assertThat(controller.delete(VEHICLE_ID).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(vehicleService).delete(USER_ID, VEHICLE_ID);
    }
}
