package ua.net.tokar.json.rainbowrest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.verify;

class RainbowRestWebFilterUnitTest {

    public static final String RELATIVE_URL = "/awesome-endpoint";
    public static final String BATCH_SERVER_URI = "https://batch.server.org";

    @Mock ExecutorService executorService;
    @Mock HttpServletRequest request;
    @Mock HttpClient httpClient;

    URI batchServerUri = new URI( BATCH_SERVER_URI );
    @Spy RainbowRestWebFilter rainbowRestWebFilter = new RainbowRestWebFilter(
            executorService,
            10,
            httpClient,
            "",
            "",
            batchServerUri
    );

    RainbowRestWebFilterUnitTest() throws URISyntaxException {
    }

    @BeforeEach
    void setup() {
        MockitoAnnotations.initMocks( this );
    }

    @Test
    void getUriMustUseExplicitBatchServerPathIfItExists() throws URISyntaxException {
        // Setup
        // Run
        rainbowRestWebFilter.getUri( request, RELATIVE_URL, Collections.emptyList() );
        // Assert
        verify( rainbowRestWebFilter ).buildUri( batchServerUri, RELATIVE_URL, Collections.emptyList() );
    }
}
