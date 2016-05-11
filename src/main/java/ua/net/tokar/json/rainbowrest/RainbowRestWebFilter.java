package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.*;
import java.io.IOException;
import java.util.*;

public class RainbowRestWebFilter extends RainbowRestOncePerRequestFilter {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_FIELDS_PARAM_NAME = "fields";
    private static final String DEFAULT_INCLUDE_PARAM_NAME = "include";
    public static final String INCLUSION_ELEMENT_ATTRIBUTE = "href";

    private String fieldsParamName = DEFAULT_FIELDS_PARAM_NAME;
    private String includeParamName = DEFAULT_INCLUDE_PARAM_NAME;

    public RainbowRestWebFilter() {
    }

    public RainbowRestWebFilter(String fieldsParamName, String includeParamName) {
        if ( StringUtils.isNotEmpty(fieldsParamName)) {
            this.fieldsParamName = fieldsParamName;
        }
        if ( StringUtils.isNotEmpty(includeParamName)) {
            this.includeParamName = includeParamName;
        }
    }

    /**
     * If you want to override default names of params for fields filtering and inclusion,
     * you could  provide new names via init-params in web.xml
     *
     *
     *    <filter>
     *        <filter-name>RainbowRestWebFilter</filter-name>
     *        <filter-class>ua.net.tokar.json.rainbowrest.RainbowRestWebFilter</filter-class>
     *        <init-param>
     *            <param-name>fields</param-name>
     *            <param-value>myFieldsName</param-value>
     *        </init-param>
     *        <init-param>
     *            <param-name>include</param-name>
     *            <param-value>exposeFieldName</param-value>
     *        </init-param>
     *    </filter>
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);

        String fieldsParamNameOverride = filterConfig.getInitParameter(DEFAULT_FIELDS_PARAM_NAME);
        if ( StringUtils.isNotEmpty(fieldsParamNameOverride) ) {
            fieldsParamName = fieldsParamNameOverride;
        }
        String includeParamNameOverride = filterConfig.getInitParameter(DEFAULT_INCLUDE_PARAM_NAME);
        if ( StringUtils.isNotEmpty(includeParamNameOverride)) {
            includeParamName = includeParamNameOverride;
        }
    }

    @Override
    protected void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HtmlResponseWrapper capturingResponseWrapper = new HtmlResponseWrapper( response );

        filterChain.doFilter(request, capturingResponseWrapper);

        String includeValue = request.getParameter(includeParamName);

        String fieldsValue = request.getParameter(fieldsParamName);
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
                if ( !node.path(INCLUSION_ELEMENT_ATTRIBUTE).isMissingNode() ) {
                    HtmlResponseWrapper copy = new HtmlResponseWrapper( response );
                    request.getRequestDispatcher( node.path( INCLUSION_ELEMENT_ATTRIBUTE ).textValue() ).forward( request, copy );

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


