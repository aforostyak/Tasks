package api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class CrtpApi {

    private static final int attemptsLimit = 2;
    private final RateLimiterProvider<Document> rateLimiterProvider;
    private final RetryProvider<Document> retryProvider;
    @Setter
    private CrtpApiService worker = new JsonCrtApiService();


    public CrtpApi(TimeUnit timeUnit, int requestLimit) {
        rateLimiterProvider = new RateLimiterProvider<>(timeUnit, requestLimit, "docCreationCrtpApiService");
        retryProvider = new RetryProvider<>(timeUnit, attemptsLimit, "rateLimitedDocCreation");
    }

    public Document createDocument(String documentBody, String signature) {
        // decided to return new empty doc; depending on requirements, can be replaced with e.g., orElseThrow
        return worker.createDocumentFromJson(documentBody, signature).orElse(new Document());
    }

    public Document createDocumentWithRequestLimits(String documentBody, String signature) {
        Supplier<Document> rateLimitedSupplier = rateLimiterProvider.getDecoratedSupplier(() -> createDocument(documentBody, signature));
        Supplier<Document> retryingSupplier = retryProvider.getDecoratedSupplier(rateLimitedSupplier);
        return retryingSupplier.get();
    }

    interface SupplierDecorator<T> {
        Supplier<T> getDecoratedSupplier(Supplier<T> supplier);
    }

    interface CrtpApiService {
        Optional<Document> createDocumentFromJson(String documentBody, String signature);
    }

    @Data
    public static class Document {
        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;
        private String production_type;
        private List<Product> products;
        private String reg_date;
        private String reg_number;
    }

    @Data
    public static class Description {
        private String participantInn;
    }

    @Data
    public static class Product {
        private String certificate_document;
        private String certificate_document_date;
        private String certificate_document_number;
        private String owner_inn;
        private String producer_inn;
        private String production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;
    }

    public class RateLimiterProvider<T> implements SupplierDecorator<T> {

        private final RateLimiter rateLimiter;

        public RateLimiterProvider(TimeUnit timeUnit, int requestLimit, String rateLimiterName) {
            RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                    .limitForPeriod(requestLimit)
                    .limitRefreshPeriod(Duration.of(1, timeUnit.toChronoUnit()))
                    .build();

            this.rateLimiter = RateLimiter.of(rateLimiterName, rateLimiterConfig);
        }

        @Override
        public Supplier<T> getDecoratedSupplier(Supplier<T> supplier) {
            return RateLimiter.decorateSupplier(rateLimiter, supplier);
        }
    }

    public class RetryProvider<T> implements SupplierDecorator<T> {

        private final Retry retry;

        public RetryProvider(TimeUnit timeUnit, int attemptsLimit, String retryName) {
            RetryConfig retryConfig = RetryConfig.custom()
                    .maxAttempts(attemptsLimit)
                    .waitDuration(Duration.of(1, timeUnit.toChronoUnit()))
                    .build();

            this.retry = Retry.of(retryName, retryConfig);
        }

        @Override
        public Supplier<T> getDecoratedSupplier(Supplier<T> supplier) {
            retry.getEventPublisher().onRetry(e -> log.error(e.toString()));
            retry.getEventPublisher().onSuccess(e -> log.error(e.toString()));
            return Retry.decorateSupplier(retry, supplier);
        }
    }

    @Getter
    @NoArgsConstructor
    public class JsonCrtApiService implements CrtpApiService {

        public static final String SIGNATURE_HEADER = "Signature";
        private static final String JSON_DOC_CREATE_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
        private final ObjectMapper objectMapper = new ObjectMapper();
        private HttpPostProvider httpPostProvider = new HttpPostProvider(JSON_DOC_CREATE_URL);

        public JsonCrtApiService(HttpPostProvider httpPostProvider) {
            this.httpPostProvider = httpPostProvider;
        }

        @Override
        public Optional<Document> createDocumentFromJson(String documentBody, String signature) {

            httpPostProvider.tune(SIGNATURE_HEADER, signature, documentBody);
            String requestResult = httpPostProvider.execute();

            Optional<Document> documentInstance = Optional.empty();
            try {
                documentInstance = Optional.ofNullable(objectMapper.readValue(requestResult, Document.class));
            } catch (JsonProcessingException e) {
                log.error("The query results are not suitable for Json serialization.\n" + e.getMessage());
            }
            return documentInstance;
        }

    }

    public class HttpPostProvider {
        private final ObjectMapper objectMapper;
        private final HttpPost httpPost;

        public HttpPostProvider(String url) {
            this.httpPost = new HttpPost(url);
            this.objectMapper = new ObjectMapper();
        }

        public void tune(String headerName, Object headerValue, Object body) {
            httpPost.setHeader(headerName, headerValue);
            try {
                String jsonBody = objectMapper.writeValueAsString(body);
                StringEntity entity = new StringEntity(jsonBody, ContentType.APPLICATION_JSON);
                httpPost.setEntity(entity);
            } catch (JsonProcessingException e) {
                log.error(e.getMessage());
            }
        }

        public String execute() {
            String entity = "";
            try (CloseableHttpClient httpClient = HttpClients.createDefault();
                 CloseableHttpResponse httpResponse = (CloseableHttpResponse) httpClient
                         .execute(httpPost, new CustomHttpClientResponseHandler())) {

                entity = httpResponse.getEntity().toString();

            } catch (IOException e) {
                log.error(e.toString());
            }
            return entity;
        }
    }

    public class CustomHttpClientResponseHandler implements HttpClientResponseHandler<ClassicHttpResponse> {
        @Override
        public ClassicHttpResponse handleResponse(ClassicHttpResponse response) {
            return response;
        }
    }

    public static void main(String[] args) throws IOException {
        CrtpApi crtpApi = new CrtpApi(TimeUnit.SECONDS, 1);
        String jsonString = Files.readString(Path.of("src/test/resources/document.json"));

        for (int i = 0; i < 3; i++) {
            crtpApi.createDocumentWithRequestLimits(jsonString, "signature");
        }
    }
}
