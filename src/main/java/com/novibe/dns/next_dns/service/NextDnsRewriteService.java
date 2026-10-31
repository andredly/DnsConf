package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.health.HealthStatus;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.novibe.common.health.HealthStatus.DOWN;
import static com.novibe.common.health.HealthStatus.UNKNOWN;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {

    private final NextDnsRewriteClient nextDnsRewriteClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;

    public Map<String, CreateRewriteDto> buildNewRewrites(
            List<BypassRoute> overrides,
            List<RewriteDto> existingRewrites,
            Map<String, HealthStatus> ipStatuses,
            boolean healthCheckEnabled) {
        Map<String, RewriteDto> existingByDomain = new LinkedHashMap<>();
        for (RewriteDto existingRewrite : existingRewrites) {
            existingByDomain.putIfAbsent(existingRewrite.name(), existingRewrite);
        }

        Map<String, List<String>> candidatesByDomain = new LinkedHashMap<>();
        for (BypassRoute route : overrides) {
            candidatesByDomain.computeIfAbsent(route.website(), domain -> new ArrayList<>()).add(route.ip());
        }

        Map<String, CreateRewriteDto> rewriteDtos = new LinkedHashMap<>();
        candidatesByDomain.forEach((domain, candidates) -> {
            if (excludeRedirectCheckService.shouldExclude(domain)) {
                return;
            }
            RewriteDto existingRewrite = existingByDomain.get(domain);
            String selectedIp = selectIp(domain, candidates, existingRewrite, ipStatuses, healthCheckEnabled);
            if (nonNull(selectedIp) && (isNull(existingRewrite) || !selectedIp.equals(existingRewrite.content()))) {
                rewriteDtos.put(domain, new CreateRewriteDto(domain, selectedIp));
            }
        });
        return rewriteDtos;
    }

    private String selectIp(
            String domain,
            List<String> candidates,
            RewriteDto existingRewrite,
            Map<String, HealthStatus> ipStatuses,
            boolean healthCheckEnabled) {
        if (!healthCheckEnabled) {
            return candidates.getFirst();
        }

        String currentIp = nonNull(existingRewrite) ? existingRewrite.content() : null;
        if (nonNull(currentIp) && statusOf(currentIp, ipStatuses) != DOWN) {
            return currentIp;
        }

        for (String candidate : candidates) {
            if (statusOf(candidate, ipStatuses) != DOWN) {
                return candidate;
            }
        }

        if (nonNull(currentIp)) {
            Log.fail("All candidate IPs are down for %s; keeping current IP %s".formatted(domain, currentIp));
        } else {
            Log.fail("All candidate IPs are down for %s; no rewrite created".formatted(domain));
        }
        return null;
    }

    private static HealthStatus statusOf(String ip, Map<String, HealthStatus> ipStatuses) {
        return ipStatuses.getOrDefault(ip, UNKNOWN);
    }

    public List<CreateRewriteDto> cleanupOutdatedAndExcluded(
            Map<String, CreateRewriteDto> newRewriteRequests,
            List<RewriteDto> existingRewrites) {
        List<String> outdatedIds = new ArrayList<>();
        List<String> ignoredIds = new ArrayList<>();

        for (RewriteDto existingRewrite : existingRewrites) {
            String domain = existingRewrite.name();
            String oldIp = existingRewrite.content();
            if (excludeRedirectCheckService.shouldExclude(domain)) {
                ignoredIds.add(existingRewrite.id());
                newRewriteRequests.remove(domain);
                continue;
            }
            CreateRewriteDto request = newRewriteRequests.get(domain);
            if (nonNull(request) && !request.content().equals(oldIp)) {
                outdatedIds.add(existingRewrite.id());
            } else {
                newRewriteRequests.remove(domain);
            }
        }
        newRewriteRequests.keySet().removeIf(excludeRedirectCheckService::shouldExclude);

        if (!outdatedIds.isEmpty()) {
            Log.io("Removing %s outdated rewrites from NextDNS".formatted(outdatedIds.size()));
            nextDnsRewriteClient.deleteRewritesByIds(outdatedIds);
        }
        if (!ignoredIds.isEmpty()) {
            Log.io("Removing %s excluded rewrites from NextDNS".formatted(ignoredIds.size()));
            nextDnsRewriteClient.deleteRewritesByIds(ignoredIds);
        }
        return List.copyOf(newRewriteRequests.values());
    }

    public List<RewriteDto> getExistingRewrites() {
        Log.io("Fetching existing rewrites from NextDNS");
        return nextDnsRewriteClient.fetchRewrites();
    }

    public void saveRewrites(List<CreateRewriteDto> createRewriteDtos) {
        Log.io("Saving %s new rewrites to NextDNS...".formatted(createRewriteDtos.size()));
        nextDnsRewriteClient.saveRewrites(createRewriteDtos);
    }

    public void removeAll() {
        Log.io("Fetching existing rewrites from NextDNS");
        List<RewriteDto> list = nextDnsRewriteClient.fetchRewrites();
        List<String> ids = list.stream().map(RewriteDto::id).toList();
        Log.io("Removing rewrites from NextDNS");
        nextDnsRewriteClient.deleteRewritesByIds(ids);
    }

}
