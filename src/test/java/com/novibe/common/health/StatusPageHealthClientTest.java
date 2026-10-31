package com.novibe.common.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusPageHealthClientTest {

    private static final String HEALTH_CHECK_URL = "https://status.test/index.json";

    @Mock
    private HttpClient httpClient;

    private StatusPageHealthClient client;

    @BeforeEach
    void setUp() {
        client = new StatusPageHealthClient(httpClient);
    }

    @Test
    void mapsOperationalDowntimeAndUnknownStatuses() throws Exception {
        doReturn(response(200, """
                {
                  "included": [
                    {"type": "status_page_resource", "attributes": {"public_name": "1.2.3.4", "status": "operational"}},
                    {"type": "status_page_resource", "attributes": {"public_name": "5.6.7.8", "status": "downtime"}},
                    {"type": "status_page_resource", "attributes": {"public_name": "9.9.9.9", "status": "degraded"}},
                    {"type": "status_page_resource", "attributes": {"public_name": "8.8.8.8", "status": "not_monitored"}},
                    {"type": "other", "attributes": {"public_name": "7.7.7.7", "status": "operational"}}
                  ]
                }
                """)).when(httpClient).send(any(), any());

        Map<String, HealthStatus> statuses = client.fetchStatuses(HEALTH_CHECK_URL);

        assertEquals(HealthStatus.UP, statuses.get("1.2.3.4"));
        assertEquals(HealthStatus.DOWN, statuses.get("5.6.7.8"));
        assertEquals(HealthStatus.UNKNOWN, statuses.get("9.9.9.9"));
        assertEquals(HealthStatus.UNKNOWN, statuses.get("8.8.8.8"));
        assertEquals(4, statuses.size());
    }

    @Test
    void failsOnHttpError() throws Exception {
        doReturn(response(503, "")).when(httpClient).send(any(), any());

        assertThrows(IOException.class, () -> client.fetchStatuses(HEALTH_CHECK_URL));
    }

    @Test
    void returnsEmptyStatusesForMalformedStructure() throws Exception {
        doReturn(response(200, "{\"included\": []}")).when(httpClient).send(any(), any());

        assertEquals(Map.of(), client.fetchStatuses(HEALTH_CHECK_URL));
    }

    private HttpResponse<String> response(int statusCode, String body) {
        HttpResponse<String> response = mock();
        lenient().when(response.statusCode()).thenReturn(statusCode);
        lenient().when(response.body()).thenReturn(body);
        return response;
    }
}
