package com.mannschaft.app.billing;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;
@Service
public class StripeBillingPriceProvisionGateway implements BillingPriceProvisionGateway {
    public ProductResolution resolveOrCreateProduct(ProductResolutionCommand c) { throw new UnsupportedOperationException("TEMP_STUB"); }
    public PriceCreationResult createPrice(PriceCreationCommand c) { throw new UnsupportedOperationException("TEMP_STUB"); }
    public Optional<PriceSnapshot> findPriceByMetadata(UUID a, UUID b) { throw new UnsupportedOperationException("TEMP_STUB"); }
}
