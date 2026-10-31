package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.novibe.common.health.HealthStatus.DOWN;
import static com.novibe.common.health.HealthStatus.UNKNOWN;
import static com.novibe.common.health.HealthStatus.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextDnsRewriteServiceTest {

    private static final String DOMAIN = "gemini.google.com";

    @Mock
    private NextDnsRewriteClient nextDnsRewriteClient;

    @Mock
    private ExcludeRedirectCheckService excludeRedirectCheckService;

    private NextDnsRewriteService service;

    @BeforeEach
    void setUp() {
        service = new NextDnsRewriteService(nextDnsRewriteClient, excludeRedirectCheckService);
    }

    @Test
    void keepsCurrentLivingIpEvenWhenAnotherIpIsFirstInSource() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "1.1.1.1"));
        List<BypassRoute> overrides = List.of(route("2.2.2.2", DOMAIN), route("1.1.1.1", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", UP, "2.2.2.2", UP), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertTrue(requests.isEmpty());
        assertTrue(toSave.isEmpty());
        verifyNoInteractions(nextDnsRewriteClient);
    }

    @Test
    void replacesCurrentDownIpWithFirstLivingCandidate() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "1.1.1.1"));
        List<BypassRoute> overrides = List.of(
                route("1.1.1.1", DOMAIN),
                route("2.2.2.2", DOMAIN),
                route("3.3.3.3", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", DOWN, "2.2.2.2", UP, "3.3.3.3", UP), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertEquals(List.of(new CreateRewriteDto(DOMAIN, "2.2.2.2")), toSave);
        verify(nextDnsRewriteClient).deleteRewritesByIds(List.of("id-1"));
    }

    @Test
    void keepsCurrentIpWhenAllCandidatesAreDown() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "1.1.1.1"));
        List<BypassRoute> overrides = List.of(route("1.1.1.1", DOMAIN), route("2.2.2.2", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", DOWN, "2.2.2.2", DOWN), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertTrue(toSave.isEmpty());
        verifyNoInteractions(nextDnsRewriteClient);
    }

    @Test
    void selectsFirstLivingIpForNewRewrite() {
        List<BypassRoute> overrides = List.of(
                route("1.1.1.1", DOMAIN),
                route("2.2.2.2", DOMAIN));
        List<RewriteDto> existing = List.of();

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", DOWN, "2.2.2.2", UP), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertEquals(List.of(new CreateRewriteDto(DOMAIN, "2.2.2.2")), toSave);
        verifyNoInteractions(nextDnsRewriteClient);
    }

    @Test
    void usesFirstSourceIpWhenHealthCheckIsDisabled() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "2.2.2.2"));
        List<BypassRoute> overrides = List.of(route("1.1.1.1", DOMAIN), route("2.2.2.2", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", DOWN, "2.2.2.2", UP), false);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertEquals(List.of(new CreateRewriteDto(DOMAIN, "1.1.1.1")), toSave);
        verify(nextDnsRewriteClient).deleteRewritesByIds(List.of("id-1"));
    }

    @Test
    void keepsCurrentIpWithUnknownStatus() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "2.2.2.2"));
        List<BypassRoute> overrides = List.of(route("1.1.1.1", DOMAIN), route("2.2.2.2", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("1.1.1.1", UP, "2.2.2.2", UNKNOWN), true);

        assertTrue(requests.isEmpty());
    }

    @Test
    void removesExcludedDomainRewrite() {
        List<RewriteDto> existing = List.of(existing("id-1", DOMAIN, "1.1.1.1"));
        when(excludeRedirectCheckService.shouldExclude(DOMAIN)).thenReturn(true);

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                List.of(route("1.1.1.1", DOMAIN)), existing, Map.of("1.1.1.1", UP), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertTrue(toSave.isEmpty());
        verify(nextDnsRewriteClient).deleteRewritesByIds(List.of("id-1"));
    }

    @Test
    void doesNotRemoveRewriteForDomainThatDisappearedFromSource() {
        List<RewriteDto> existing = List.of(existing("id-1", "old.com", "1.1.1.1"));
        List<BypassRoute> overrides = List.of(route("2.2.2.2", DOMAIN));

        Map<String, CreateRewriteDto> requests = service.buildNewRewrites(
                overrides, existing, Map.of("2.2.2.2", UP), true);
        List<CreateRewriteDto> toSave = service.cleanupOutdatedAndExcluded(requests, existing);

        assertEquals(List.of(new CreateRewriteDto(DOMAIN, "2.2.2.2")), toSave);
        verifyNoInteractions(nextDnsRewriteClient);
    }

    private static BypassRoute route(String ip, String domain) {
        return new BypassRoute(ip, domain);
    }

    private static RewriteDto existing(String id, String domain, String ip) {
        return new RewriteDto(id, domain, ip);
    }
}
