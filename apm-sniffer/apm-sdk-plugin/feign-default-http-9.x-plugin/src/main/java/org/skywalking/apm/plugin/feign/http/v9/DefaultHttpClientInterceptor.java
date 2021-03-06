package org.skywalking.apm.plugin.feign.http.v9;

import feign.Request;
import feign.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.context.ContextCarrier;
import org.skywalking.apm.agent.core.context.ContextManager;
import org.skywalking.apm.agent.core.context.tag.Tags;
import org.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.skywalking.apm.network.trace.component.ComponentsDefine;

/**
 * {@link DefaultHttpClientInterceptor} intercept the default implementation of http calls by the Feign.
 *
 * @author pengys5
 */
public class DefaultHttpClientInterceptor implements InstanceMethodsAroundInterceptor {

    private static final String COMPONENT_NAME = "FeignDefaultHttp";

    /**
     * Get the {@link feign.Request} from {@link EnhancedInstance}, then create {@link AbstractSpan} and set host,
     * port, kind, component, url from {@link feign.Request}.
     * Through the reflection of the way, set the http header of context data into {@link feign.Request#headers}.
     *
     * @param result change this result, if you want to truncate the method.
     * @throws Throwable
     */
    @Override public void beforeMethod(EnhancedInstance objInst, String methodName, Object[] allArguments,
        Class<?>[] argumentsTypes, MethodInterceptResult result) throws Throwable {
        Request request = (Request)allArguments[0];

        URL url = new URL(request.url());
        ContextCarrier contextCarrier = new ContextCarrier();
        String remotePeer = url.getHost() + ":" + url.getPort();
        AbstractSpan span = ContextManager.createExitSpan(request.url(), contextCarrier, remotePeer);
        span.setComponent(ComponentsDefine.FEIGN);
        Tags.HTTP.METHOD.set(span, request.method());
        Tags.URL.set(span, url.getPath());
        SpanLayer.asHttp(span);

        List<String> contextCollection = new ArrayList<String>();
        contextCollection.add(contextCarrier.serialize());

        Field headersField = Request.class.getDeclaredField("headers");
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(headersField, headersField.getModifiers() & ~Modifier.FINAL);

        headersField.setAccessible(true);
        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
        headers.put(Config.Plugin.Propagation.HEADER_NAME, contextCollection);
        headers.putAll(request.headers());

        headersField.set(request, Collections.unmodifiableMap(headers));
    }

    /**
     * Get the status code from {@link Response}, when status code greater than 400, it means there was some errors in
     * the server.
     * Finish the {@link AbstractSpan}.
     *
     * @param ret the method's original return value.
     * @return
     * @throws Throwable
     */
    @Override public Object afterMethod(EnhancedInstance objInst, String methodName, Object[] allArguments,
        Class<?>[] argumentsTypes, Object ret) throws Throwable {
        Response response = (Response)ret;
        int statusCode = response.status();

        AbstractSpan span = ContextManager.activeSpan();
        if (statusCode >= 400) {
            span.errorOccurred();
            Tags.STATUS_CODE.set(span, statusCode + "");
        }

        ContextManager.stopSpan();

        return ret;
    }

    @Override public void handleMethodException(EnhancedInstance objInst, String methodName, Object[] allArguments,
        Class<?>[] argumentsTypes, Throwable t) {
        AbstractSpan activeSpan = ContextManager.activeSpan();
        activeSpan.log(t);
        activeSpan.errorOccurred();
    }
}
