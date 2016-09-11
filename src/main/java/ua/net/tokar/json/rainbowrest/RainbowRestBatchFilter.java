package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URL;
import java.util.Map;

public class RainbowRestBatchFilter extends RainbowRestOncePerRequestFilter {
    private static final String BATCH_ENDPOINT_URI_PARAM_NAME = "batchEndpointUri";
    private static final String BATCH_ENDPOINT_METHOD = "POST";
    private static final String DEFAULT_BATCH_ENDPOINT_URI = "/batch";

    private String batchEndpointUri = DEFAULT_BATCH_ENDPOINT_URI;

    private ObjectMapper mapper = new ObjectMapper();


    public RainbowRestBatchFilter() {
    }

    public RainbowRestBatchFilter(String batchEndpointUri) {
        if (StringUtils.isNotEmpty(batchEndpointUri)) {
            this.batchEndpointUri = batchEndpointUri;
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);

        String batchEndpointUriOverride = filterConfig.getInitParameter(BATCH_ENDPOINT_URI_PARAM_NAME);
        if (StringUtils.isNotEmpty(batchEndpointUriOverride)) {
            batchEndpointUri = batchEndpointUriOverride;
        }
    }

    @Override
    protected void doFilterInternal(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        if (
                ((HttpServletRequest)request).getMethod().equalsIgnoreCase(BATCH_ENDPOINT_METHOD)
             && ((HttpServletRequest)request).getRequestURI().equalsIgnoreCase(batchEndpointUri)
        ) {
            Map<String, String> map = mapper.readValue(
                    request.getInputStream(),
                    new TypeReference<Map<String,String>>() {}
            );

            ObjectNode tree = mapper.createObjectNode();
            for (Map.Entry<String, String> stringStringEntry : map.entrySet()) {
                tree.set(
                        stringStringEntry.getKey(),
                        mapper.readTree( new URL(
                                request.getScheme(),
                                request.getServerName(),
                                request.getServerPort(),
                                stringStringEntry.getValue()
                        ) )
                );
            }

            response.getWriter().write(tree.toString());
        } else {
            doFilter( request, response, filterChain );
        }
    }
}
