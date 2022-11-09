package ua.net.tokar.json.rainbowrest;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import ua.net.tokar.json.rainbowrest.exception.RequestProcessingTimedOutException;
import ua.net.tokar.json.rainbowrest.exception.RequestProcessingFailedException;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.*;

abstract class RainbowRestOncePerRequestFilter implements Filter {

    private static final int DEFAULT_EXECUTION_TIMEOUT_SECONDS = 10;
    private static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";
    private static final String DEFAULT_EXECUTION_TIMEOUT_SECONDS_PARAM_NAME = "executionTimeoutSeconds";
    private static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";

    private ExecutorService executorService;
    private int executionTimeoutSeconds;
    private HttpClient httpClient;
    private URI batchServerUri;

    public RainbowRestOncePerRequestFilter() {
        this(
                Executors.newCachedThreadPool(),
                DEFAULT_EXECUTION_TIMEOUT_SECONDS,
                new ApacheHttpClientAdapter()
        );
    }

    public RainbowRestOncePerRequestFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds
    ) {
        this( executorService, executionTimeoutSeconds, new ApacheHttpClientAdapter() );
    }

    public RainbowRestOncePerRequestFilter(
            HttpClient httpClient
    ) {
        this(
                Executors.newCachedThreadPool(),
                DEFAULT_EXECUTION_TIMEOUT_SECONDS,
                httpClient
        );
    }

    public RainbowRestOncePerRequestFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            HttpClient httpClient
    ) {
        this.executorService = executorService;
        this.executionTimeoutSeconds = executionTimeoutSeconds;
        this.httpClient = httpClient;
    }

    public RainbowRestOncePerRequestFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            HttpClient httpClient,
            URI batchServerUri
    ) {
        this( executorService, executionTimeoutSeconds, httpClient );
        this.batchServerUri = batchServerUri;
    }

    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
        String executionTimeoutSecondsOverride =
                filterConfig.getInitParameter( DEFAULT_EXECUTION_TIMEOUT_SECONDS_PARAM_NAME );
        if ( StringUtils.isNotEmpty( executionTimeoutSecondsOverride ) ) {
            executionTimeoutSeconds = Integer.valueOf( executionTimeoutSecondsOverride );
        }
    }

    @Override
    public void destroy() {

    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        boolean hasAlreadyFilteredAttribute = request.getAttribute( getAlreadyFilteredAttributeName() ) != null;

        if ( hasAlreadyFilteredAttribute ) {
            filterChain.doFilter( request, response );
        } else {
            request.setAttribute( getAlreadyFilteredAttributeName(), Boolean.TRUE );
            try {
                doFilterInternal( request, response, filterChain );
            } finally {
                request.removeAttribute( getAlreadyFilteredAttributeName() );
            }
        }
    }

    private String getAlreadyFilteredAttributeName() {
        return getClass().getName() + ALREADY_FILTERED_SUFFIX;
    }

    protected String getResponseViaInternalDispatching(
            String relativeUrl,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        JsonResponseWrapper responseWrapper = new JsonResponseWrapper( response );
        request.getRequestDispatcher( relativeUrl )
               .forward(
                       new GetHttpServletRequest( (HttpServletRequest) request ),
                       responseWrapper
               );

        return responseWrapper.getCaptureAsString();
    }

    protected String getResponseViaInternalDispatching(
            URI uri,
            List<HttpHeader> headers
    ) throws IOException, URISyntaxException {
        return httpClient.getResponseBody( uri, headers );
    }

    protected URI getUri( HttpServletRequest request, String relativeUrl, List<NameValuePair> requestParams )
            throws URISyntaxException {
        return batchServerUri == null
                ? buildUri( request, relativeUrl, requestParams )
                : buildUri( batchServerUri, relativeUrl, requestParams );
    }

    protected URI buildUri(
            ServletRequest request,
            String relativeUrl,
            Collection<NameValuePair> additionalRequestParams
    ) throws URISyntaxException {
        String path = getPath( relativeUrl );
        List<NameValuePair> params = getParams( relativeUrl, additionalRequestParams );
        return new URIBuilder()
                .setScheme( request.getScheme() )
                .setHost( request.getLocalName() )
                .setPort( request.getLocalPort() )
                .setPath( path )
                .setParameters( params )
                .build();
    }

    protected URI buildUri(
            URI batchServerUri,
            String relativeUrl,
            Collection<NameValuePair> additionalRequestParams
    ) throws URISyntaxException {
        String path = getPath( relativeUrl );
        List<NameValuePair> params = getParams( relativeUrl, additionalRequestParams );
        return new URIBuilder()
                .setScheme( batchServerUri.getScheme() )
                .setHost( batchServerUri.getHost() )
                .setPort( batchServerUri.getPort() )
                .setPath( path )
                .setParameters( params )
                .build();
    }

    private List<NameValuePair> getParams(
            String relativeUrl,
            Collection<NameValuePair> additionalRequestParams
    ) throws URISyntaxException {
        List<NameValuePair> params = new ArrayList<>();
        params.addAll( URLEncodedUtils.parse(
                new URI( relativeUrl ),
                StandardCharsets.UTF_8
        ) );
        params.addAll( additionalRequestParams );
        return params;
    }

    private String getPath( String relativeUrl ) {
        int questionMarkIndex = relativeUrl.indexOf( '?' );
        return questionMarkIndex != -1
                ? relativeUrl.substring( 0, relativeUrl.indexOf( '?' ) )
                : relativeUrl;
    }

    protected List<HttpHeader> getHeaders( HttpServletRequest request ) {
        List<HttpHeader> headers = new ArrayList<>();
        for ( Enumeration<String> e = request.getHeaderNames(); e.hasMoreElements(); ) {
            String name = e.nextElement();
            if ( CONTENT_LENGTH_HEADER_NAME.equalsIgnoreCase( name ) ) {
                continue;
            }
            headers.add( new HttpHeader( name, request.getHeader( name ) ) );
        }
        return headers;
    }

    protected abstract void doFilterInternal(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException;

    protected <T> List<T> executeInParallel(
            List<Callable<T>> callables
    ) {
        List<Future<T>> futures = new ArrayList<>();
        try {
            callables.forEach( c -> futures.add( executorService.submit( c ) ) );
        } catch ( RejectedExecutionException e ) {
            for ( Future<T> f : futures ) {
                f.cancel( true );
            }
            throw new RequestProcessingFailedException( e );
        }

        List<T> result = new ArrayList<>();
        for ( Future<T> future : futures ) {
            try {
                result.add( future.get( executionTimeoutSeconds, TimeUnit.SECONDS ) );
            } catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException( e );
            } catch ( ExecutionException e ) {
                throw new RuntimeException( e.getCause() );
            } catch ( TimeoutException e ) {
                future.cancel( true );
                throw new RequestProcessingTimedOutException( e );
            }
        }
        return result;
    }

    private static class GetHttpServletRequest extends HttpServletRequestWrapper {
        private static final String GET_METHOD = "GET";

        public GetHttpServletRequest( HttpServletRequest request ) {
            super( request );
        }

        @Override
        public String getMethod() {
            return GET_METHOD;
        }
    }
}
