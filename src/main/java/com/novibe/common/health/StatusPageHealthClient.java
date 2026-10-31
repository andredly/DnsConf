package com.novibe.common.health;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatusPageHealthClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final String STATUS_PAGE_RESOURCE = "status_page_resource";

    private final HttpClient httpClient;

    public Map<String, HealthStatus> fetchStatuses(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() > 299) {
            throw new IOException("Health check page returned response code " + response.statusCode());
        }
        return parseStatuses(response.body());
    }

    private Map<String, HealthStatus> parseStatuses(String body) {
        Map<String, HealthStatus> statuses = new LinkedHashMap<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return statuses;
        }

        JsonArray included = root.getAsJsonObject().getAsJsonArray("included");
        if (included == null) {
            return statuses;
        }

        for (JsonElement element : included) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            if (!STATUS_PAGE_RESOURCE.equals(stringValue(resource, "type"))) {
                continue;
            }
            JsonElement attributesElement = resource.get("attributes");
            if (attributesElement == null || !attributesElement.isJsonObject()) {
                continue;
            }
            JsonObject attributes = attributesElement.getAsJsonObject();
            String ip = stringValue(attributes, "public_name");
            if (ip == null || ip.isBlank()) {
                continue;
            }
            statuses.put(ip.strip(), mapStatus(stringValue(attributes, "status")));
        }
        return statuses;
    }

    private static HealthStatus mapStatus(String status) {
        if (status == null) {
            return HealthStatus.UNKNOWN;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "operational" -> HealthStatus.UP;
            case "downtime" -> HealthStatus.DOWN;
            default -> HealthStatus.UNKNOWN;
        };
    }

    private static String stringValue(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
