package org.skywalking.apm.plugin.mongodb.v3;

import com.mongodb.MongoNamespace;
import com.mongodb.operation.FindOperation;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.codecs.Decoder;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.skywalking.apm.agent.core.context.trace.LogDataEntity;
import org.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.skywalking.apm.agent.core.context.util.KeyValuePair;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.skywalking.apm.agent.test.helper.SegmentHelper;
import org.skywalking.apm.agent.test.helper.SpanHelper;
import org.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.skywalking.apm.agent.test.tools.SegmentStorage;
import org.skywalking.apm.agent.test.tools.SegmentStoragePoint;
import org.skywalking.apm.agent.test.tools.TracingSegmentRunner;

import static junit.framework.TestCase.assertNotNull;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;
import static org.skywalking.apm.agent.test.tools.SpanAssert.assertException;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
public class MongoDBMethodInterceptorTest {

    @SegmentStoragePoint
    private SegmentStorage segmentStorage;

    @Rule
    public AgentServiceRule serviceRule = new AgentServiceRule();

    private MongoDBMethodInterceptor interceptor;

    @Mock
    private EnhancedInstance enhancedInstance;

    private Object[] arguments;
    private Class[] argumentTypes;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Before
    public void setUp() throws Exception {

        interceptor = new MongoDBMethodInterceptor();

        Config.Plugin.MongoDB.TRACE_PARAM = true;

        when(enhancedInstance.getSkyWalkingDynamicField()).thenReturn("127.0.0.1:27017");

        BsonDocument document = new BsonDocument();
        document.append("name", new BsonString("by"));
        MongoNamespace mongoNamespace = new MongoNamespace("test.user");
        Decoder decoder = PowerMockito.mock(Decoder.class);
        FindOperation findOperation = new FindOperation(mongoNamespace, decoder);
        findOperation.filter(document);

        arguments = new Object[] {findOperation};
        argumentTypes = new Class[] {findOperation.getClass()};
    }

    @Test
    public void testIntercept() throws Throwable {
        interceptor.beforeMethod(enhancedInstance, "FindOperation", arguments, argumentTypes, null);
        interceptor.afterMethod(enhancedInstance, "FindOperation", arguments, argumentTypes, null);

        MatcherAssert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
        TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
        assertRedisSpan(spans.get(0));
    }

    @Test
    public void testInterceptWithException() throws Throwable {
        interceptor.beforeMethod(enhancedInstance, "FindOperation", arguments, argumentTypes, null);
        interceptor.handleMethodException(enhancedInstance, "FindOperation", arguments, argumentTypes, new RuntimeException());
        interceptor.afterMethod(enhancedInstance, "FindOperation", arguments, argumentTypes, null);

        MatcherAssert.assertThat(segmentStorage.getTraceSegments().size(), is(1));
        TraceSegment traceSegment = segmentStorage.getTraceSegments().get(0);
        List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegment);
        assertRedisSpan(spans.get(0));
        List<LogDataEntity> logDataEntities = SpanHelper.getLogs(spans.get(0));
        assertThat(logDataEntities.size(), is(1));
        assertException(logDataEntities.get(0), RuntimeException.class);
    }

    private void assertRedisSpan(AbstractTracingSpan span) {
        assertThat(span.getOperationName(), is("MongoDB/FindOperation"));
        assertThat(SpanHelper.getComponentId(span), is(9));
        List<KeyValuePair> tags = SpanHelper.getTags(span);
        assertThat(tags.get(1).getValue(), is("FindOperation { \"name\" : \"by\" }"));
        assertThat(tags.get(0).getValue(), is("MongoDB"));
        assertThat(span.isExit(), is(true));
        assertThat(SpanHelper.getLayer(span), is(SpanLayer.DB));
    }

}
