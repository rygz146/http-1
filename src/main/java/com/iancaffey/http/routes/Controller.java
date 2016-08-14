package com.iancaffey.http.routes;

import com.iancaffey.http.Request;
import com.iancaffey.http.Response;
import com.iancaffey.http.Route;
import com.iancaffey.http.io.HttpWriter;
import com.iancaffey.http.io.RequestVisitor;
import com.iancaffey.http.util.RoutingException;
import com.iancaffey.http.util.URIPattern;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Controller
 * <p>
 * An object that routes incoming HTTP requests to an appropriate method for response.
 * <p>
 * Controller is thread-safe, making use of a thread-local response to ensure the {@code Route} located within the routing table
 * when visiting the request data is consistent until when {@code Router#response(HttpWriter)} is called.
 * <p>
 * Controller provides a means to provide dynamic, parameterized routing mechanisms rather than a static {@code Router}.
 *
 * @author Ian Caffey
 * @since 1.0
 */
public class Controller implements RequestVisitor {
    private final Map<String, Map<String, Method>> directRoutes = new HashMap<>();
    private final Map<String, Map<Pattern, Method>> patternRoutes = new HashMap<>();
    private final Map<Method, Pattern> compiledPatterns = new HashMap<>();
    private final ThreadLocal<Method> selected = new ThreadLocal<>();
    private final ThreadLocal<Request> request = new ThreadLocal<>();

    /**
     * Constructs a new {@code Controller}, identifying all methods with {@code Route} annotations.
     */
    public Controller() {
        Class<?> c = getClass();
        do {
            Stream.concat(Arrays.stream(c.getMethods()), Arrays.stream(c.getDeclaredMethods())).forEach(method -> {
                if (!method.isAnnotationPresent(Route.class))
                    return;
                if (!Response.class.isAssignableFrom(method.getReturnType()))
                    throw new IllegalArgumentException("Routes must return a Response or a subclass of Response. " + method);
                Route route = method.getAnnotation(Route.class);
                String requestType = route.requestType();
                String path = route.value();
                if (path.isEmpty())
                    throw new IllegalArgumentException("Route paths must be non-empty.");
                if (Route.ROUTE_PATTERN.matcher(path).find()) {
                    if (patternRoutes.containsKey(requestType)) {
                        Pattern p = URIPattern.compile(path);
                        compiledPatterns.put(method, p);
                        patternRoutes.get(requestType).put(p, method);
                    } else {
                        Map<Pattern, Method> typePatternRoutes = new HashMap<>();
                        Pattern p = URIPattern.compile(path);
                        compiledPatterns.put(method, p);
                        typePatternRoutes.put(p, method);
                        patternRoutes.put(requestType, typePatternRoutes);
                    }
                } else {
                    if (directRoutes.containsKey(requestType)) {
                        directRoutes.get(requestType).put(path, method);
                    } else {
                        Map<String, Method> typeDirectRoutes = new HashMap<>();
                        typeDirectRoutes.put(path, method);
                        directRoutes.put(requestType, typeDirectRoutes);
                    }

                }
            });
        } while ((c = c.getSuperclass()) != null);
    }

    /**
     * Returns the current request being handled by the controller.
     * <p>
     * Requests are thread-local and this method should only be called within methods that have a {@code Route} annotation.
     * <p>
     * Outdated requests will be useless as the socket used to send responses to clients would have been closed.
     *
     * @return the current request being handled by the controller
     */
    public Request request() {
        return request.get();
    }

    /**
     * Locates the best route that matches the request type and uri.
     * <p>
     * Direct routes are searched first, and then each value-based route is checked for a full match against the uri.
     * <p>
     * If no direct routes are found and there are no full-match value-based routes, an attempt to find a partial match
     * for a value-based routes will be made.
     * <p>
     *
     * @param requestType the request type
     * @param uri         the uri
     * @return the best response that matched the uri
     */
    public Method find(String requestType, String uri) {
        if (directRoutes.containsKey(requestType)) {
            Map<String, Method> typeDirectRoutes = directRoutes.get(requestType);
            if (typeDirectRoutes.containsKey(uri))
                return typeDirectRoutes.get(uri);
        }
        if (!patternRoutes.containsKey(requestType))
            return null;
        Map<Pattern, Method> typePatternRoutes = patternRoutes.get(requestType);
        for (Map.Entry<Pattern, Method> entry : typePatternRoutes.entrySet())
            if (entry.getKey().matcher(uri).matches())
                return entry.getValue();
        for (Map.Entry<Pattern, Method> entry : typePatternRoutes.entrySet())
            if (entry.getKey().matcher(uri).find())
                return entry.getValue();
        return null;
    }

    /**
     * Visits the beginning header entry that details out request type, uri, and HTTP version.
     *
     * @param requestType the request type
     * @param uri         the uri
     * @param version     the HTTP version
     */
    @Override
    public final void visitRequest(String requestType, String uri, String version) {
        selected.set(find(requestType, uri));
        request.set(new Request(requestType, uri, version, new HashMap<>()));
    }

    /**
     * Visits a request header entry.
     *
     * @param key   the request header key
     * @param value the request header value
     */
    @Override
    public final void visitHeader(String key, String value) {
        Request request = this.request.get();
        if (request == null)
            throw new RoutingException("No active request being handled.");
        request.header(key, value);
    }

    /**
     * Responds to a HTTP request. Responses are not restricted to be done within the caller thread.
     * <p>
     * The {@code RequestVisitor} is responsible for writing the response out and closing the {@code HttpWriter} to
     * complete the response for the request.
     *
     * @param writer the response writer
     * @throws Exception indicating there was an error writing out the response
     */
    @Override
    public final void respond(HttpWriter writer) throws Exception {
        Request request = this.request.get();
        if (request == null)
            throw new RoutingException("No active request being handled.");
        Method method = selected.get();
        if (method == null)
            throw new RoutingException("Unable to find route.");
        request.body(writer.in());
        method.setAccessible(true);
        if (Route.ROUTE_PATTERN.matcher(method.getAnnotation(Route.class).value()).find()) {
            Matcher matcher = compiledPatterns.get(method).matcher(request.uri());
            if (matcher.find()) {
                int groups = matcher.groupCount();
                int parameters = method.getParameterCount();
                if (groups != parameters)
                    throw new IllegalStateException("Parameter mismatch. Unable to parse uri into arguments for route.");
                Class<?>[] parameterTypes = method.getParameterTypes();
                Object[] objects = new Object[parameters];
                for (int i = 0; i < objects.length; i++) {
                    String s = matcher.group(i + 1);
                    Class<?> type = parameterTypes[i];
                    if (type == String.class)
                        objects[i] = s;
                    else {
                        try {
                            if (type == int.class || type == Integer.class)
                                objects[i] = Integer.parseInt(s);
                            else if (type == long.class || type == Long.class)
                                objects[i] = Long.parseLong(s);
                            else if (type == float.class || type == Float.class)
                                objects[i] = Float.parseFloat(s);
                            else if (type == double.class || type == Double.class)
                                objects[i] = Double.parseDouble(s);
                        } catch (Exception e) {
                            throw new RoutingException("Type mismatch. Unable to parse uri into arguments for route.");
                        }
                    }
                }
                writer.write((Response) method.invoke(this, objects));
                writer.close();
            }
        } else {
            writer.write((Response) method.invoke(this));
            writer.close();
        }
    }
}
