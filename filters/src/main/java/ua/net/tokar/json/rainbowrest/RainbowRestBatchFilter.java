package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class RainbowRestBatchFilter extends RainbowRestOncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger( RainbowRestBatchFilter.class );

    private static final String BATCH_ENDPOINT_URI_PARAM_NAME = "batchEndpointUri";
    private static final String BATCH_ENDPOINT_METHOD = "POST";
    private static final String DEFAULT_BATCH_ENDPOINT_URI = "/batch";

    private String batchEndpointUri = DEFAULT_BATCH_ENDPOINT_URI;
    private Integer maxBatchSize;

    private ObjectMapper mapper = new ObjectMapper();


    public RainbowRestBatchFilter() {
    }

    public RainbowRestBatchFilter( String batchEndpointUri ) {
        if ( StringUtils.isNotEmpty( batchEndpointUri ) ) {
            this.batchEndpointUri = batchEndpointUri;
        }
    }

    public RainbowRestBatchFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            String batchEndpointUri
    ) {
        super( executorService, executionTimeoutSeconds );
        this.batchEndpointUri = batchEndpointUri;
    }

    public RainbowRestBatchFilter(
            HttpClient httpClient,
            String batchEndpointUri
    ) {
        super( httpClient );
        this.batchEndpointUri = batchEndpointUri;
    }


    public RainbowRestBatchFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            HttpClient httpClient,
            String batchEndpointUri,
            Integer maxBatchSize
    ) {
        super( executorService, executionTimeoutSeconds, httpClient );
        this.batchEndpointUri = batchEndpointUri;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
        super.init( filterConfig );

        String batchEndpointUriOverride = filterConfig.getInitParameter(
                BATCH_ENDPOINT_URI_PARAM_NAME
        );
        if ( StringUtils.isNotEmpty( batchEndpointUriOverride ) ) {
            batchEndpointUri = batchEndpointUriOverride;
        }
    }

    @Override
    protected void doFilterInternal(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        if ( !httpServletRequest.getMethod().equalsIgnoreCase( BATCH_ENDPOINT_METHOD )
                || !httpServletRequest.getRequestURI().equalsIgnoreCase( batchEndpointUri ) ) {
            doFilter( request, response, filterChain );
        } else {
            Map<String, String> map;
            try {
                map = mapper.readValue(
                        request.getInputStream(),
                        new TypeReference<Map<String, String>>() {}
                );
            } catch ( IOException e ) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        "Incorrect batch request body. Expected example(use double quotes): {'key1':'/someRelativePath', 'key2':'/anotherRelativePath'}"
                );
                return;
            }
            if ( !isValidBatchSize( map ) ) {
                sendError(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        String.format(
                                "Incorrect batch size. Max size=%d",
                                maxBatchSize
                        )
                );
                return;
            }
            response.setContentType( "application/json" );
            response.getWriter().write(
                    getBatchResults(
                            map,
                            (HttpServletRequest) request
                    ).toString() );
        }
    }

    private ObjectNode getBatchResults( Map<String, String> map, HttpServletRequest request ) {
        final ObjectNode tree = mapper.createObjectNode();
        List<HttpHeader> headers = getHeaders( request );

        List<Callable<JsonNodeResult>> callables =
                map.entrySet()
                   .stream()
                   .map( nameToUrl -> (Callable<JsonNodeResult>) ( () -> {
                       String relativeUrl = nameToUrl.getValue();
                       JsonNode jsonNode = null;
                       try {
                           jsonNode = mapper.readTree(
                                   getResponseViaInternalDispatching(
                                           buildUri(
                                                   request,
                                                   relativeUrl,
                                                   Collections.emptyList()
                                           ),
                                           headers
                                   )
                           );
                       } catch ( IOException e ) {
                           // TODO provide error message
                       } catch ( URISyntaxException e ) {
                           log.warn( "Cannot build URI for relativeUrl='{}'", relativeUrl );
                       }
                       return new JsonNodeResult( jsonNode, nameToUrl.getKey() );
                   } ) )
                   .collect( Collectors.toList() );

        executeInParallel( callables ).forEach( result -> tree.set(
                result.getFieldName(),
                result.getJsonNode()
        ) );
        return tree;
    }

    private static class JsonNodeResult {
        private JsonNode jsonNode;
        private String fieldName;

        public JsonNodeResult( JsonNode jsonNode, String fieldName ) {
            this.jsonNode = jsonNode;
            this.fieldName = fieldName;
        }

        public JsonNode getJsonNode() {
            return jsonNode;
        }

        public String getFieldName() {
            return fieldName;
        }
    }

    private void sendError( ServletResponse response, int responseCode, String message )
            throws IOException {
        log.warn( message );
        ( (HttpServletResponse) response ).sendError(
                responseCode,
                message
        );
    }

    private boolean isValidBatchSize( Map<String, String> map ) {
        return Objects.isNull( maxBatchSize ) || map.size() <= maxBatchSize;
    }
}
