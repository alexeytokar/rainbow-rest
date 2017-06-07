package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.*;

public class RainbowRestWebFilter extends RainbowRestOncePerRequestFilter {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_FIELDS_PARAM_NAME = "fields";
    private static final String DEFAULT_INCLUDE_PARAM_NAME = "include";
    private static final String INCLUSION_ELEMENT_ATTRIBUTE = "href";
    private static final String EXCLUDE_FIELDS_INIT_SYMBOL = "-";

    private String fieldsParamName = DEFAULT_FIELDS_PARAM_NAME;
    private String includeParamName = DEFAULT_INCLUDE_PARAM_NAME;

    public RainbowRestWebFilter() {
    }

    public RainbowRestWebFilter( String fieldsParamName, String includeParamName ) {
        if ( StringUtils.isNotEmpty( fieldsParamName ) ) {
            this.fieldsParamName = fieldsParamName;
        }
        if ( StringUtils.isNotEmpty( includeParamName ) ) {
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

        String fieldsParamNameOverride = filterConfig.getInitParameter( DEFAULT_FIELDS_PARAM_NAME );
        if ( StringUtils.isNotEmpty( fieldsParamNameOverride ) ) {
            fieldsParamName = fieldsParamNameOverride;
        }
        String includeParamNameOverride = filterConfig.getInitParameter( DEFAULT_INCLUDE_PARAM_NAME );
        if ( StringUtils.isNotEmpty( includeParamNameOverride ) ) {
            includeParamName = includeParamNameOverride;
        }
    }

    @Override
    protected void doFilterInternal(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        String includeValue = request.getParameter( includeParamName );
        String fieldsValue = request.getParameter( fieldsParamName );
        if ( StringUtils.isEmpty( includeValue ) && StringUtils.isEmpty( fieldsValue ) ) {
            filterChain.doFilter( request, response );
            return;
        }

        HtmlResponseWrapper capturingResponseWrapper = new HtmlResponseWrapper( response );
        filterChain.doFilter( request, capturingResponseWrapper );

        Set<String> includeFields = new HashSet<>();
        Set<String> excludeFields = new HashSet<>();
        if ( !StringUtils.isEmpty( fieldsValue ) ) {
            for ( String field : fieldsValue.split( "," ) ) {
                if ( field.startsWith( EXCLUDE_FIELDS_INIT_SYMBOL ) ) {
                    excludeFields.add( field.substring( EXCLUDE_FIELDS_INIT_SYMBOL.length() ) );
                } else {
                    includeFields.add( field );
                }
            }
        }

        Set<String> include = new HashSet<>();
        if ( !StringUtils.isEmpty( includeValue ) ) {
            include.addAll( Arrays.asList(
                    includeValue.split( "," )
            ) );
        }

        String content = capturingResponseWrapper.getCaptureAsString();
        JsonNode tree = mapper.readTree( content );
        if ( !include.isEmpty() ) {
            processIncludes( tree, include, request, response );
        }
        if ( !includeFields.isEmpty() || !excludeFields.isEmpty() ) {
            filterTree( tree, includeFields, excludeFields );
        }

        response.getWriter().write( tree.toString() );
    }

    private void processIncludes(
            JsonNode tree,
            Set<String> include,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        List<String> inc = new ArrayList<>( include );
        Collections.sort( inc ); // TODO sort parent nodes first. count dots in name

        for ( String s : inc ) {
            processSingleInclude( tree, s.split( "\\." ), 0, request, response );
        }
    }

    private void processSingleInclude(
            JsonNode tree,
            String[] s,
            int index,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        JsonNode parent = tree;
        JsonNode node = tree;
        String nodeName = "";
        for ( int i = index; i < s.length; ++i ) {
            nodeName = s[i];
            parent = node;
            if ( node.isArray() ) {
                for ( final Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    processSingleInclude( it.next(), s, i, request, response );
                }
            }
            node = node.path( s[i] );
            if ( node.isMissingNode() ) {
                break;
            }
        }

        if ( !node.isMissingNode() ) {
            if ( node.isArray() ) {
                ArrayNode newArrayNode = mapper.createArrayNode();
                for ( final Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    newArrayNode.add( createNodeForInclude( it.next(), request, response ) );
                }
                ( (ObjectNode) parent ).set( nodeName, newArrayNode );
            } else {
                ( (ObjectNode) parent ).set(
                        nodeName,
                        createNodeForInclude( node, request, response )
                );
            }
        }
    }

    private JsonNode createNodeForInclude(
            JsonNode node,
            ServletRequest request,
            ServletResponse response
    ) throws ServletException, IOException {
        if ( node.path( INCLUSION_ELEMENT_ATTRIBUTE ).isMissingNode() ) {
            return node;
        }

        HtmlResponseWrapper copy = new HtmlResponseWrapper( response );
        request.getRequestDispatcher( node.path( INCLUSION_ELEMENT_ATTRIBUTE ).textValue() )
               .forward( new GetHttpServletRequest( (HttpServletRequest) request ), copy );

        return mapper.readTree( copy.getCaptureAsString() );
    }

    private void filterTree(
            JsonNode tree,
            Set<String> includedFields,
            Set<String> excludedFields
    ) {
        if ( tree.isArray() ) {
            for ( final Iterator<JsonNode> it = tree.elements(); it.hasNext(); ) {
                filterTree( it.next(), includedFields, excludedFields );
            }
        } else if ( tree.isObject() ) {
            for ( final Iterator<Map.Entry<String, JsonNode>> it = tree.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                final String key = entry.getKey();
                if ( ( !includedFields.isEmpty() && !includedFields.contains( key ) ) ||
                        excludedFields.contains( key ) ) {
                    it.remove();
                } else {
                    filterTree( entry.getValue(), includedFields, excludedFields );
                }
            }
        }
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


