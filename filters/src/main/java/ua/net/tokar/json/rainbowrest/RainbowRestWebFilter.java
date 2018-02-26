package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.Callable;

public class RainbowRestWebFilter extends RainbowRestOncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger( RainbowRestWebFilter.class );

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
            processIncludes( tree, include, request );
        }
        if ( !includeFields.isEmpty() || !excludeFields.isEmpty() ) {
            filterTree( tree, includeFields, excludeFields );
        }

        response.getWriter().write( tree.toString() );
    }

    private void processIncludes(
            JsonNode tree,
            Set<String> include,
            ServletRequest request
    ) throws ServletException, IOException {
        List<String> inc = new ArrayList<>( include );
        Collections.sort( inc ); // TODO sort parent nodes first. count dots in name

        for ( String s : inc ) {
            processSingleInclude( tree, s.split( "\\." ), 0, request );
        }
    }

    private Void processSingleInclude(
            JsonNode tree,
            String[] s,
            int index,
            ServletRequest request
    ) throws ServletException, IOException {
        JsonNode parent = tree;
        JsonNode node = tree;
        String nodeName = "";
        for ( int i = index; i < s.length; ++i ) {
            nodeName = s[i];
            parent = node;
            if ( node.isArray() ) {
                List<Callable<Void>> callables = new ArrayList<>();
                final int finalIndex = i;
                for ( final Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    JsonNode currentNode = it.next();
                    callables.add( () -> processSingleInclude( currentNode, s, finalIndex, request ) );
                }
                executeInParallel( callables );
            }
            node = node.path( s[i] );
            if ( node.isMissingNode() ) {
                break;
            }
        }

        if ( !node.isMissingNode() ) {
            if ( node.isArray() ) {
                List<Callable<JsonNode>> callables = new ArrayList<>();
                for ( final Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    callables.add( () -> createNodeForInclude( it.next(), request ) );
                }

                ArrayNode newArrayNode = mapper.createArrayNode();
                newArrayNode.addAll( executeInParallel( callables ) );
                ( (ObjectNode) parent ).set( nodeName, newArrayNode );
            } else {
                ( (ObjectNode) parent ).set(
                        nodeName,
                        createNodeForInclude( node, request )
                );
            }
        }
        return null;
    }

    private JsonNode createNodeForInclude(
            JsonNode node,
            ServletRequest request
    ) throws IOException {
        if ( node.path( INCLUSION_ELEMENT_ATTRIBUTE ).isMissingNode() ) {
            return node;
        }

        Header[] headers = getHeaders( (HttpServletRequest) request );
        String relativeUrl = node.path( INCLUSION_ELEMENT_ATTRIBUTE ).textValue();
        try {
            return mapper.readTree(
                    getResponseViaInternalDispatching( buildUri( request, relativeUrl ), headers )
            );
        } catch ( URISyntaxException e ) {
            log.warn( "Cannot build URI for relativeUrl='{}'", relativeUrl );
            return node;
        }
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

}


