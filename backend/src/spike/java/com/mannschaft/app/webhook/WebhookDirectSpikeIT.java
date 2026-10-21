package com.mannschaft.app.webhook;

import org.springframework.modulith.test.ApplicationModuleTest;

/** 同じ実Bean検体を公式DIRECT_DEPENDENCIES modeで確認する。 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
class WebhookDirectSpikeIT extends WebhookSpikeIT { }
