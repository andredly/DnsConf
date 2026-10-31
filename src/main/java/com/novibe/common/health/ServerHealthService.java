package com.novibe.common.health;

import com.novibe.common.config.EnvironmentVariables;
import com.novibe.common.util.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static com.novibe.common.health.HealthStatus.UNKNOWN;

@Service
public class ServerHealthService {

    private final StatusPageHealthClient statusPageHealthClient;
    private final String healthCheckUrl;

    private Map<String, HealthStatus> statuses = Map.of();
    private boolean statusesLoaded;

    @Autowired
    public ServerHealthService(StatusPageHealthClient statusPageHealthClient) {
        this(statusPageHealthClient, EnvironmentVariables.HEALTH_CHECK_URL);
    }

    ServerHealthService(StatusPageHealthClient statusPageHealthClient, String healthCheckUrl) {
        this.statusPageHealthClient = statusPageHealthClient;
        this.healthCheckUrl = healthCheckUrl == null ? null : healthCheckUrl.strip();
    }

    public boolean isEnabled() {
        return healthCheckUrl != null && !healthCheckUrl.isBlank();
    }

    public Map<String, HealthStatus> check(Collection<String> ips) {
        Map<String, HealthStatus> result = new LinkedHashMap<>();
        LinkedHashSet<String> uniqueIps = new LinkedHashSet<>(ips);

        if (!isEnabled()) {
            uniqueIps.forEach(ip -> result.put(ip, UNKNOWN));
            return result;
        }

        Map<String, HealthStatus> pageStatuses = loadStatuses();
        uniqueIps.forEach(ip -> result.put(ip, pageStatuses.getOrDefault(ip, UNKNOWN)));
        return result;
    }

    private Map<String, HealthStatus> loadStatuses() {
        if (statusesLoaded) {
            return statuses;
        }
        statusesLoaded = true;
        try {
            statuses = statusPageHealthClient.fetchStatuses(healthCheckUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.fail("Failed to load health check statuses: " + e.getMessage());
        } catch (Exception e) {
            Log.fail("Failed to load health check statuses: " + e.getMessage());
        }
        return statuses;
    }
}
