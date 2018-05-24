package ua.net.tokar.json.rainbowrest;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public interface HttpClient {
    String getResponseBody( URI uri, List<HttpHeader> headers ) throws IOException;
}
