package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URL;
import java.util.Map;

public class RainbowRestBatchFilter extends RainbowRestOncePerRequestFilter {
    private static final String BATCH_ENDPOINT_METHOD = "POST";
    private static final String BATCH_ENDPOINT_URI = "/batch";

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        if (
                ((HttpServletRequest)request).getMethod().equalsIgnoreCase(BATCH_ENDPOINT_METHOD)
                        && ((HttpServletRequest)request).getRequestURI().equalsIgnoreCase(BATCH_ENDPOINT_URI)
                ) {
            Map<String, String> map = mapper.readValue(request.getInputStream(), Map.class);

            ObjectNode tree = mapper.createObjectNode();
            for (Map.Entry<String, String> stringStringEntry : map.entrySet()) {
                tree.put(
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
