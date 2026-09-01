package com.mannschaft.app.billing.api;

/** PR4 Stripe return callback の未実装骨格。 */
public class BillingReturnController {
    public String checkoutSuccess(String state) { throw new IllegalArgumentException("generic return error"); }
    public String checkoutCancel(String state) { throw new IllegalArgumentException("generic return error"); }
}
