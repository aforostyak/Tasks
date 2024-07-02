package api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("A JSONCrtpApi Service")
class JsonCrtpApiSpec {

    private final CrtpApi crtpApi = new CrtpApi(TimeUnit.SECONDS, 3);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should create Document instance according to given Json")
    void shouldCreateDocInstanceAccordingToGivenJson() throws IOException {

        String signature = "signature";

        String jsonString = Files.readString(Path.of("src/test/resources/document.json"));
        CrtpApi.Document document = objectMapper.readValue(jsonString, CrtpApi.Document.class);

        CrtpApi.HttpPostProvider mockHttpPostProvider = mock(CrtpApi.HttpPostProvider.class);
        CrtpApi.JsonCrtApiService jsonCrtApiService = crtpApi.new JsonCrtApiService(mockHttpPostProvider);

        when(mockHttpPostProvider.execute()).thenReturn(jsonString);

        assertThat(jsonCrtApiService.createDocumentFromJson(jsonString, signature).get())
                .isEqualTo(document);

        verify(mockHttpPostProvider).tune(CrtpApi.JsonCrtApiService.SIGNATURE_HEADER, signature, jsonString);
    }
}