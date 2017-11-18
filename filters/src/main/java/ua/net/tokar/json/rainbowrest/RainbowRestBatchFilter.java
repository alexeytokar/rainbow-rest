package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;

public class RainbowRestBatchFilter extends RainbowRestOncePerRequestFilter {
    private static final String BATCH_ENDPOINT_URI_PARAM_NAME = "batchEndpointUri";
    private static final String BATCH_ENDPOINT_METHOD = "POST";
    private static final String DEFAULT_BATCH_ENDPOINT_URI = "/batch";

    private String batchEndpointUri = DEFAULT_BATCH_ENDPOINT_URI;

    private ObjectMapper mapper = new ObjectMapper();


    public RainbowRestBatchFilter() {
    }

    public RainbowRestBatchFilter( String batchEndpointUri ) {
        if ( StringUtils.isNotEmpty( batchEndpointUri ) ) {
            this.batchEndpointUri = batchEndpointUri;
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);

        String batchEndpointUriOverride = filterConfig.getInitParameter(BATCH_ENDPOINT_URI_PARAM_NAME);
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
        if (
                !httpServletRequest.getMethod().equalsIgnoreCase( BATCH_ENDPOINT_METHOD )
                        || !httpServletRequest.getRequestURI().equalsIgnoreCase( batchEndpointUri )
                ) {
            doFilter( request, response, filterChain );
        } else {
            Map<String, String> map = mapper.readValue(
                    request.getInputStream(),
                    new TypeReference<Map<String,String>>() {}
            );

            final ObjectNode tree = mapper.createObjectNode();
            Header[] headers = getHeaders( (HttpServletRequest) request );

            map.entrySet()
               .parallelStream()
               .forEach( nameToUrl -> {
                   JsonNode jsonNode = null;
                   try {
                       jsonNode = mapper.readTree(
                               getResponseViaInternalDispatching(
                                       buildUri( request, nameToUrl.getValue() ),
                                       headers
                               )
                       );
                   } catch ( IOException e ) {
                       // TODO provide error message
                   } catch ( ServletException e ) {
                       // TODO catch servlet exception
                   } catch ( URISyntaxException e ) {
                       // TODO provide error message
                   }
                   tree.set( nameToUrl.getKey(), jsonNode );
               } );
            response.setContentType( "application/json" );
            response.getWriter().write( tree.toString() );
        }
    }
}
