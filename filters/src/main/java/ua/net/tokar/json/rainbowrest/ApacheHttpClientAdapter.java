package ua.net.tokar.json.rainbowrest;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class ApacheHttpClientAdapter implements HttpClient {
    private org.apache.http.client.HttpClient httpClient;

    public ApacheHttpClientAdapter() {
        httpClient = HttpClients.createDefault();
    }

    public ApacheHttpClientAdapter( org.apache.http.client.HttpClient httpClient ) {
        this.httpClient = httpClient;
    }

    @Override
    public String getResponseBody( URI uri, List<HttpHeader> headers ) throws IOException {
        HttpGet httpGet = new HttpGet( uri );
        List<Header> apacheHeaders = headers
                .stream()
                .map( this::transform )
                .collect( Collectors.toList() );
        httpGet.setHeaders( apacheHeaders.toArray(new Header[apacheHeaders.size()]) );
        HttpEntity entity = httpClient.execute( httpGet ).getEntity();
        return entity != null ? EntityUtils.toString( entity, StandardCharsets.UTF_8 ) : null;
    }

    private Header transform( HttpHeader header ) {
        return new BasicHeader( header.getName(), header.getValue() );
    }
}
