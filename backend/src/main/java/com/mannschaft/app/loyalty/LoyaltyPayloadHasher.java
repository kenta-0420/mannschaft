package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.dto.RedeemRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 再送照合用の型付きpayload。raw tokenの解析やQR再検証には依存しない。 */
@Component
public class LoyaltyPayloadHasher {
    public byte[] staffAward(Long team, StaffAwardRequest body) { return new byte[32]; }
    public byte[] selfSubmit(Long team, SelfAwardRequest body) { return new byte[32]; }
    public byte[] redeem(Long team, RedeemRequest body) { return new byte[32]; }
    public byte[] approve(Long team, UUID request, ApproveRequest body) { return new byte[32]; }
    public byte[] action(RequestOperation operation, Long team, UUID request, RequestActionRequest body) {
        return new byte[32];
    }
}
