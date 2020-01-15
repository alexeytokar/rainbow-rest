package ua.net.tokar.json.rainbowrest;

import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class Include {

    private final String includeFieldName;
    private final Collection<Param> requestParams = new ArrayList<>();

    public Include(
            String includeFieldName,
            String requestParams
    ) {
        this.includeFieldName = includeFieldName;
        if ( StringUtils.isNotEmpty( requestParams ) ) {
            this.requestParams.addAll( parseRequestParamsFromInclude( requestParams ) );
        }
    }

    private Collection<Param> parseRequestParamsFromInclude( String requestParams ) {
        return Arrays.stream( requestParams.split( "," ) )
                     .map( param -> {
                         String[] nameValue = param.split( ":" );
                         if ( nameValue.length != 2 ) {
                             return null;
                         }
                         return new Param(
                                 nameValue[0],
                                 nameValue[1]
                         );
                     } )
                     .filter( Objects::nonNull )
                     .collect( Collectors.toList() );
    }

    public String getIncludeFieldName() {
        return includeFieldName;
    }

    public Collection<Param> getRequestParams() {
        return Collections.unmodifiableCollection( requestParams );
    }
}
