package ua.net.tokar.json.rainbowrest;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

public class BasicHttpClient implements HttpClient {
    private org.apache.http.client.HttpClient httpClient;

    public BasicHttpClient() {
        httpClient = HttpClients.createDefault();
    }

    public BasicHttpClient( org.apache.http.client.HttpClient httpClient ) {
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
        return entity != null ? EntityUtils.toString( entity ) : null;
    }

    private Header transform( HttpHeader header ) {
        return new BasicHeader( header.getName(), header.getValue() );
    }
}
