package ru.otus.socialnetwork.api;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.otus.socialnetwork.service.SessionService;

@Configuration(proxyBeanMethods = false)
public class BearerAuthentication implements WebMvcConfigurer, HandlerMethodArgumentResolver {
    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) +([^ ]+)");
    private final SessionService sessions;

    public BearerAuthentication(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthenticatedUser.class;
    }

    @Override
    public AuthenticatedUser resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                             NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String[] headers = request.getHeaderValues(HttpHeaders.AUTHORIZATION);
        if (headers == null || headers.length != 1) {
            throw ApiException.unauthorized();
        }
        var bearer = BEARER.matcher(headers[0]);
        if (!bearer.matches()) {
            throw ApiException.unauthorized();
        }
        int id = sessions.resolveUserId(bearer.group(1)).orElseThrow(ApiException::unauthorized);
        return new AuthenticatedUser(id);
    }

    public record AuthenticatedUser(int id) {}
}
