package api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("A CrtpApt Service")
class CrtpApiSpec {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final CrtpApi crtpApi = new CrtpApi(TimeUnit.MICROSECONDS, 3);
    private static final CrtpApi.JsonCrtApiService mockJsonCrtApiService = mock(CrtpApi.JsonCrtApiService.class);

    private static final String signature = "signature_test";
    private static String jsonString;
    private static CrtpApi.Document document;

    @BeforeAll
    static void init() throws IOException {
        crtpApi.setWorker(mockJsonCrtApiService);
        jsonString = Files.readString(Path.of("src/test/resources/document.json"));
        document = objectMapper.readValue(jsonString, CrtpApi.Document.class);

        when(mockJsonCrtApiService.createDocumentFromJson(jsonString, signature))
                .thenReturn(Optional.of(document));
    }

    @Test
    @DisplayName("should create Document instance according to given Json")
    void shouldCreateDocInstanceAccordingToGivenJson() {

        assertThat(crtpApi.createDocumentWithRequestLimits(jsonString, signature))
                .isEqualTo(document);

    }

    @Test
    @DisplayName("should create empty Document instance from given incorrect JSON")
    void shouldCreateEmptyDocInstanceFromGivenIncorrectJson() {

        assertThat(crtpApi.createDocumentWithRequestLimits("incorrect JSON", signature))
                .isEqualTo(new CrtpApi.Document());

    }
}