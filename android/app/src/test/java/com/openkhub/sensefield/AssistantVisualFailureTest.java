package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AssistantVisualFailureTest {
    @Test public void unknownReasonIsBoundedAndDoesNotRequestAnUnchangedRetry() {
        assertEquals("invalid_output", AssistantVisualFailure.unknownReason("invalid_output", true));
        assertEquals("empty_answer", AssistantVisualFailure.unknownReason("PRIVATE SERVER TEXT", true));
        assertEquals("model_uncertain", AssistantVisualFailure.unknownReason("PRIVATE SERVER TEXT", false));
        assertEquals("这张截图没能判断清楚。", AssistantVisualFailure.unknownMessage("model_uncertain"));
        assertFalse(AssistantVisualFailure.unknownMessage("model_uncertain").contains("重新提问"));
        assertTrue(AssistantVisualFailure.unknownMessage("invalid_output").contains("格式异常"));
    }
    @Test public void heroOldGateway502RetainsUpstreamAuthAttribution() {
        String code = AssistantVisualFailure.code(502, "provider", 401, "vision_upstream_unavailable");
        assertEquals("vision_provider_auth_failed", code);
        assertTrue(AssistantVisualFailure.requiresServiceRepair(code));
        assertTrue(AssistantVisualFailure.message(code).contains("账户余额和接入配置"));
        assertFalse(AssistantVisualFailure.message(code).contains("密钥错误"));
        assertFalse(AssistantVisualFailure.message(code).contains("稍后再试"));
        assertEquals("vision_provider_access_denied", AssistantVisualFailure.code(
                502, "provider", 403, "vision_upstream_unavailable"));
    }

    @Test public void gatewayCredentialsAndTransientFailuresAreDifferent() {
        assertEquals("gateway_auth_failed", AssistantVisualFailure.code(401, "unknown", -1, "invalid_response"));
        assertEquals("gateway_access_denied", AssistantVisualFailure.code(403, "unknown", -1, "invalid_response"));
        assertEquals("rate_limited", AssistantVisualFailure.code(429, "provider", 429, "rate_limited"));
        assertEquals("vision_upstream_unavailable", AssistantVisualFailure.code(
                502, "provider", 500, "vision_upstream_unavailable"));
        assertFalse(AssistantVisualFailure.requiresServiceRepair("timeout"));
        assertFalse(AssistantVisualFailure.requiresServiceRepair("network_unavailable"));
    }
    @Test public void gatewayTimeoutReportsItsCauseEvenWhenProxyBodyIsNotJson() {
        for (String bodyCode : new String[] {"invalid_response", "vision_timeout", "vision_upstream_unavailable"}) {
            String code = AssistantVisualFailure.code(504, "unknown", -1, bodyCode);
            assertEquals("timeout", code);
            assertEquals("画面读取超时，请重试。", AssistantVisualFailure.message(code));
            assertFalse(AssistantVisualFailure.requiresServiceRepair(code));
        }
        assertEquals("画面读取超时，请重试。", AssistantVisualFailure.message("vision_timeout"));
        assertEquals("vision_provider_auth_failed", AssistantVisualFailure.code(
                504, "provider", 401, "invalid_response"));
    }

    @Test public void authFailureStopsAutomaticCallsAcrossWindowsButManualRecoveryResumes() {
        AssistantPolicy policy = new AssistantPolicy();
        policy.started(0, true, 1);
        policy.serviceFailed("vision_provider_auth_failed");
        assertFalse(policy.automaticDue(30_000, FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 2));
        assertFalse(policy.automaticDue(AssistantPolicy.WINDOW_MS + 1,
                FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 3));
        assertTrue(policy.manualAllowed(AssistantPolicy.WINDOW_MS + 1, FrameProcessingPolicy.Mode.NORMAL));
        policy.serviceFailed("timeout");
        assertFalse(policy.automaticDue(AssistantPolicy.WINDOW_MS + 2,
                FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 3));
        policy.serviceRecovered();
        assertTrue(policy.automaticDue(AssistantPolicy.WINDOW_MS + 2,
                FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 3));
        policy.throttled(AssistantPolicy.WINDOW_MS + 3);
        assertFalse(policy.manualAllowed(AssistantPolicy.WINDOW_MS + 4, FrameProcessingPolicy.Mode.NORMAL));
    }
}
