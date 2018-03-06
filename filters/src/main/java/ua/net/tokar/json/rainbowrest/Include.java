package ua.net.tokar.json.rainbowrest;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Include {

    private final String includeFieldName;
    private final List<Param> requestParams = new ArrayList<>();

    public Include(
            String includeFieldName,
            String requestParams
    ) {
        this.includeFieldName = includeFieldName;
        if ( StringUtils.isNotEmpty( requestParams ) ) {
            this.requestParams.addAll( parseRequestParamsFromInclude( requestParams ) );
        }
    }

    private List<Param> parseRequestParamsFromInclude( String requestParams ) {
        return Arrays.stream( requestParams.split( "," ) )
                     .map( param -> {
                         String[] nameValue = param.split( ":" );
                         return new Param(
                                 nameValue[0],
                                 nameValue[1]
                         );
                     } )
                     .collect( Collectors.toList() );
    }

    public String getIncludeFieldName() {
        return includeFieldName;
    }

    public List<Param> getRequestParams() {
        return requestParams;
    }
}
