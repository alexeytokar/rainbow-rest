package ua.net.tokar.json.rainbowrest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RainbowRestWebFilter extends RainbowRestOncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger( RainbowRestWebFilter.class );

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_FIELDS_PARAM_NAME = "fields";
    private static final String DEFAULT_INCLUDE_PARAM_NAME = "include";
    private static final String INCLUSION_ELEMENT_ATTRIBUTE = "href";
    private static final String EXCLUDE_FIELDS_INIT_SYMBOL = "-";
    private static final Pattern INCLUDES_PATTERN = Pattern.compile( "([^,()]+)(\\((.+?)\\))*", Pattern.CASE_INSENSITIVE);
    private static final Comparator<Include> INCLUDE_PARTS_COMPARATOR = Comparator.comparing( Include::getIncludeFieldName );

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

    public RainbowRestWebFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            String fieldsParamName,
            String includeParamName
    ) {
        super( executorService, executionTimeoutSeconds );
        this.fieldsParamName = fieldsParamName;
        this.includeParamName = includeParamName;
    }

    public RainbowRestWebFilter(
            HttpClient httpClient,
            String fieldsParamName,
            String includeParamName
    ) {
        super( httpClient );
        this.fieldsParamName = fieldsParamName;
        this.includeParamName = includeParamName;
    }

    public RainbowRestWebFilter(
            ExecutorService executorService,
            int executionTimeoutSeconds,
            HttpClient httpClient,
            String fieldsParamName,
            String includeParamName
    ) {
        super( executorService, executionTimeoutSeconds, httpClient );
        this.fieldsParamName = fieldsParamName;
        this.includeParamName = includeParamName;
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

        JsonResponseWrapper capturingResponseWrapper = new JsonResponseWrapper( response );
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

        List<Include> include = new ArrayList<>();
        if ( !StringUtils.isEmpty( includeValue ) ) {
            Matcher matcher = INCLUDES_PATTERN.matcher( includeValue);
            while (matcher.find()) {
                include.add( new Include( matcher.group( 1 ), matcher.group( 3 ) ) );
            }
        }

        String content = capturingResponseWrapper.getCaptureAsString();
        JsonNode tree = mapper.readTree( content );
        if ( tree == null ) {
            return;
        }
        if ( !include.isEmpty() ) {
            processIncludes( tree, include, request );
        }
        if ( !includeFields.isEmpty() || !excludeFields.isEmpty() ) {
            filterTree( tree, includeFields, excludeFields );
        }

        response.setContentType( "application/json" );
        mapper.writeValue(response.getOutputStream(), tree);
    }

    private void processIncludes(
            JsonNode tree,
            Collection<Include> include,
            ServletRequest request
    ) throws IOException {
        List<Include> inc = new ArrayList<>( include );
        inc.sort( INCLUDE_PARTS_COMPARATOR ); // TODO sort parent nodes first. count dots in name

        for ( Include part : inc ) {
            processSingleInclude( tree, part.getIncludeFieldName().split( "\\." ), 0, request, part.getRequestParams() );
        }
    }

    private void processSingleInclude(
            JsonNode tree,
            String[] s,
            int index,
            ServletRequest request,
            Collection<Param> requestParamsFromInclude
    ) throws IOException {
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
                    callables.add( () -> {
                        processSingleInclude(
                                currentNode,
                                s,
                                finalIndex,
                                request,
                                requestParamsFromInclude
                        );
                        return null;
                    } );
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
                    JsonNode element = it.next();
                    callables.add( () -> createNodeForInclude(
                            element,
                            request,
                            requestParamsFromInclude
                    ) );
                }

                ArrayNode newArrayNode = mapper.createArrayNode();
                newArrayNode.addAll( executeInParallel( callables ) );
                ( (ObjectNode) parent ).set( nodeName, newArrayNode );
            } else {
                ( (ObjectNode) parent ).set(
                        nodeName,
                        createNodeForInclude( node, request, requestParamsFromInclude )
                );
            }
        }
    }

    private JsonNode createNodeForInclude(
            JsonNode node,
            ServletRequest request,
            Collection<Param> requestParamsFromInclude
    ) throws IOException {
        if ( node.path( INCLUSION_ELEMENT_ATTRIBUTE ).isMissingNode() ) {
            return node;
        }

        List<HttpHeader> headers = getHeaders( (HttpServletRequest) request );
        String relativeUrl = node.path( INCLUSION_ELEMENT_ATTRIBUTE ).textValue();
        try {
            return mapper.readTree(
                    getResponseViaInternalDispatching( buildUri(
                            request,
                            relativeUrl,
                            requestParamsFromInclude.stream()
                                                    .map( param -> new BasicNameValuePair(
                                                            param.getName(),
                                                            param.getValue()
                                                    ) )
                                                    .collect( Collectors.toList() )
                    ), headers )
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


