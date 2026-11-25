package com.finsight.forecast;

import com.finsight.api.DownstreamServiceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static com.finsight.forecast.ForecastModels.*;

/** HTTP client for the Python FastAPI forecasting service. */
@Component
public class ForecastClient {
    static final String SERVICE_UNAVAILABLE_MESSAGE = "Forecasting service is unavailable or timed out";
    private static final String VALIDATION_FALLBACK_MESSAGE = "Forecasting service rejected the request";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public ForecastClient(
            @Value("${forecast.base-url}") String baseUrl,
            @Value("${forecast.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${forecast.read-timeout-ms:10000}") long readTimeoutMs,
            ObjectMapper objectMapper) {
        this(configureBuilder(RestClient.builder(), baseUrl, connectTimeoutMs, readTimeoutMs), objectMapper);
    }

    ForecastClient(RestClient.Builder builder) {
        this(builder, JsonMapper.builder().build());
    }

    ForecastClient(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    private static RestClient.Builder configureBuilder(
            RestClient.Builder builder,
            String baseUrl,
            long connectTimeoutMs,
            long readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("forecast.base-url must not be blank");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Forecast client timeouts must be positive");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.baseUrl(baseUrl.trim()).requestFactory(requestFactory);
    }

    public PythonForecastResponse forecast(PythonForecastRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Forecast request is required");
        }
        try {
            PythonForecastResponse response = restClient.post()
                    .uri("/forecast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PythonForecastResponse.class);
            if (response == null || response.model() == null || response.forecasts().isEmpty()) {
                throw new DownstreamServiceException(503, "FORECAST_SERVICE_UNAVAILABLE", SERVICE_UNAVAILABLE_MESSAGE);
            }
            return response;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400 || status == 422) {
                throw new DownstreamServiceException(422, "FORECAST_VALIDATION_ERROR", extractValidationDetail(ex), ex);
            }
            throw new DownstreamServiceException(503, "FORECAST_SERVICE_UNAVAILABLE", SERVICE_UNAVAILABLE_MESSAGE, ex);
        } catch (ResourceAccessException ex) {
            throw new DownstreamServiceException(503, "FORECAST_SERVICE_UNAVAILABLE", SERVICE_UNAVAILABLE_MESSAGE, ex);
        } catch (RestClientException ex) {
            throw new DownstreamServiceException(503, "FORECAST_SERVICE_UNAVAILABLE", SERVICE_UNAVAILABLE_MESSAGE, ex);
        }
    }

    private String extractValidationDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return VALIDATION_FALLBACK_MESSAGE;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode detail = root.get("detail");
            if (detail == null || detail.isNull()) {
                return VALIDATION_FALLBACK_MESSAGE;
            }
            if (detail.isString()) {
                String text = sanitizeMessage(detail.asString());
                return text.isEmpty() ? VALIDATION_FALLBACK_MESSAGE : text;
            }
            if (detail.isArray() && !detail.isEmpty()) {
                List<String> messages = new ArrayList<>();
                for (JsonNode item : detail) {
                    JsonNode msg = item.get("msg");
                    if (msg != null && msg.isString()) {
                        String cleaned = sanitizeMessage(msg.asString());
                        if (!cleaned.isEmpty()) {
                            messages.add(cleaned);
                        }
                    }
                }
                if (!messages.isEmpty()) {
                    return String.join("; ", messages);
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to safe generic message if downstream returned non-JSON or unexpected structure.
        }
        return VALIDATION_FALLBACK_MESSAGE;
    }

    private static String sanitizeMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String singleLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() > 500 ? singleLine.substring(0, 500) : singleLine;
    }
}
