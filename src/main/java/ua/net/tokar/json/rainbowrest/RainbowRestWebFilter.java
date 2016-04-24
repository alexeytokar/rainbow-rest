package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.*;
import java.io.IOException;
import java.util.*;

public class RainbowRestWebFilter implements Filter {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String FIELDS_PARAM_NAME = "fields";
    private static final String INCLUDE_PARAM_NAME = "include";
    private static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

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
        boolean hasAlreadyFilteredAttribute = request.getAttribute(getAlreadyFilteredAttributeName()) != null;

        if (hasAlreadyFilteredAttribute ) {
            filterChain.doFilter(request, response);
        } else {
            request.setAttribute(getAlreadyFilteredAttributeName(), Boolean.TRUE);
            try {
                doFilterInternal(request, response, filterChain);
            } finally {
                request.removeAttribute(getAlreadyFilteredAttributeName());
            }
        }
    }
    private String getAlreadyFilteredAttributeName() {
        return getClass().getName() + ALREADY_FILTERED_SUFFIX;
    }

    private void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HtmlResponseWrapper capturingResponseWrapper = new HtmlResponseWrapper( response );

        filterChain.doFilter(request, capturingResponseWrapper);

        String includeValue = request.getParameter(INCLUDE_PARAM_NAME);

        String fieldsValue = request.getParameter(FIELDS_PARAM_NAME);
        Set<String> fields = new HashSet<>();
        if (!StringUtils.isEmpty( fieldsValue )) {
            fields.addAll( Arrays.asList(
                    fieldsValue.split(",")
            ) );
        }

        Set<String> include = new HashSet<>();
        if ( !StringUtils.isEmpty( includeValue )) {
            include.addAll( Arrays.asList(
                    includeValue.split(",")
            ));
        }
        String content = capturingResponseWrapper.getCaptureAsString();
        if ( fields.isEmpty() && include.isEmpty() ) {
            response.getWriter().write( content );
        } else {
            JsonNode tree = mapper.readTree(content);

            if ( !include.isEmpty() ) {
                processIncludes(tree, include, request, response);
            }
            if ( !fields.isEmpty() ) {
                filterTree(tree, fields);
            }

            response.getWriter().write( tree.toString() );
        }
    }

    private void processIncludes(
            JsonNode tree,
            Set<String> include,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        List<String> inc = new ArrayList<>( include );
        Collections.sort( inc ); // TODO sort parent nodes first. count dots in name
        JsonNode parent = tree;
        for (String s : inc) {
            JsonNode node = tree;
            String nodeName = "";
            for (String s1 : s.split("\\.")) {
                nodeName = s1;
                parent = node;
                node = node.path( s1 );
                if ( node.isMissingNode() ) {
                    break;
                }
            }

            if ( !node.isMissingNode() ) {
                if ( !node.path( "href" ).isMissingNode() ) {
                    HtmlResponseWrapper copy = new HtmlResponseWrapper( response );
                    request.getRequestDispatcher( node.path( "href" ).textValue() ).forward( request, copy );

                    JsonNode subtree = mapper.readTree(copy.getCaptureAsString());
                    ((ObjectNode)parent).set(nodeName, subtree );
                }
            }
        }
    }
    private void filterTree(JsonNode tree, Set<String> includedFields ) {
        if (tree.isObject()) {
            for (final Iterator<Map.Entry<String,JsonNode>> it = tree.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                final String key = entry.getKey();
                if ( !includedFields.contains( key ) ) {
                    it.remove();
                } else {
                    filterTree( entry.getValue(), includedFields );
                }
            }
        }
    }
}


