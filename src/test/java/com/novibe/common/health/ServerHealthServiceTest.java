package com.novibe.common.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.novibe.common.health.HealthStatus.DOWN;
import static com.novibe.common.health.HealthStatus.UNKNOWN;
import static com.novibe.common.health.HealthStatus.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerHealthServiceTest {

    private static final String HEALTH_CHECK_URL = "https://status.test/index.json";

    @Mock
    private StatusPageHealthClient statusPageHealthClient;

    @Test
    void returnsUnknownForAllIpsWhenHealthCheckIsDisabled() {
        ServerHealthService service = new ServerHealthService(statusPageHealthClient, null);

        Map<String, HealthStatus> statuses = service.check(List.of("1.2.3.4", "5.6.7.8"));

        assertEquals(Map.of("1.2.3.4", UNKNOWN, "5.6.7.8", UNKNOWN), statuses);
        verifyNoInteractions(statusPageHealthClient);
    }

    @Test
    void returnsStatusesForUniqueIpsAndCachesThemForRun() throws Exception {
        when(statusPageHealthClient.fetchStatuses(HEALTH_CHECK_URL)).thenReturn(Map.of(
                "1.2.3.4", UP,
                "5.6.7.8", DOWN));
        ServerHealthService service = new ServerHealthService(statusPageHealthClient, HEALTH_CHECK_URL);

        Map<String, HealthStatus> first = service.check(List.of("1.2.3.4", "1.2.3.4", "5.6.7.8"));
        Map<String, HealthStatus> second = service.check(List.of("1.2.3.4", "9.9.9.9"));

        assertEquals(Map.of("1.2.3.4", UP, "5.6.7.8", DOWN), first);
        assertEquals(Map.of("1.2.3.4", UP, "9.9.9.9", UNKNOWN), second);
        verify(statusPageHealthClient, times(1)).fetchStatuses(HEALTH_CHECK_URL);
    }

    @Test
    void returnsUnknownForAllIpsWhenStatusPageFails() throws Exception {
        when(statusPageHealthClient.fetchStatuses(HEALTH_CHECK_URL)).thenThrow(new IOException("unavailable"));
        ServerHealthService service = new ServerHealthService(statusPageHealthClient, HEALTH_CHECK_URL);

        Map<String, HealthStatus> statuses = service.check(List.of("1.2.3.4", "5.6.7.8"));

        assertEquals(Map.of("1.2.3.4", UNKNOWN, "5.6.7.8", UNKNOWN), statuses);
        verify(statusPageHealthClient, times(1)).fetchStatuses(HEALTH_CHECK_URL);
    }
}
