package ua.net.tokar.json.rainbowrest;

import javax.servlet.*;
import java.io.IOException;

public abstract class RainbowRestOncePerRequestFilter implements Filter {
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

    protected abstract void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException;
}
