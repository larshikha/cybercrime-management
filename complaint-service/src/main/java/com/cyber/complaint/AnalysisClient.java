package com.cyber.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** REST client used by complaint-service to call analysis-service (inter-service communication). */
@Component
public class AnalysisClient {
    private static final Logger log = LoggerFactory.getLogger(AnalysisClient.class);
    private final RestClient client;

    public AnalysisClient(@Value("${analysis.service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(10000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    /** Returns the analysis JSON, or null if analysis-service is unreachable. */
    public JsonNode analyze(Map<String, Object> body) {
        try {
            return client.post().uri("/api/analyze").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("analysis-service call failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean isUp() {
        try {
            client.get().uri("/api/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
