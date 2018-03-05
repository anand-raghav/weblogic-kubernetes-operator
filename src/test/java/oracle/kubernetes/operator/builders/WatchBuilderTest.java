// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.
package oracle.kubernetes.operator.builders;

import com.meterware.pseudoserver.HttpUserAgentTest;
import com.meterware.pseudoserver.PseudoServlet;
import com.meterware.pseudoserver.WebResource;
import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import com.squareup.okhttp.Call;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1Service;
import io.kubernetes.client.models.V1beta1Ingress;
import oracle.kubernetes.TestUtils;
import oracle.kubernetes.operator.domain.model.oracle.kubernetes.weblogic.domain.v1.Domain;
import oracle.kubernetes.operator.helpers.ClientHelper;
import oracle.kubernetes.operator.helpers.ClientHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static oracle.kubernetes.operator.LabelConstants.DOMAINUID_LABEL;
import static oracle.kubernetes.operator.builders.EventMatcher.*;
import static oracle.kubernetes.operator.builders.WatchBuilderTest.JsonServletAction.withResponses;
import static oracle.kubernetes.operator.builders.WatchBuilderTest.ParameterValidation.parameter;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests watches created by the WatchBuilder, verifying that they are created with the correct query URLs and
 * handle http response correctly. Uses PseudoServer to provide an in-memory test web server.
 */
public class WatchBuilderTest extends HttpUserAgentTest {

    private static final String API_VERSION = "weblogic.oracle/v1";
    private static final String NAMESPACE = "testspace";
    private static final String DOMAIN_RESOURCE = "/apis/weblogic.oracle/v1/namespaces/" + NAMESPACE + "/domains";
    private static final String SERVICE_RESOURCE = "/api/v1/namespaces/" + NAMESPACE + "/services";
    private static final String POD_RESOURCE = "/api/v1/namespaces/" + NAMESPACE + "/pods";
    private static final String INGRESS_RESOURCE = "/apis/extensions/v1beta1/namespaces/" + NAMESPACE + "/ingresses";
    private static final String EOL = "\n";
    private static final int INITIAL_RESOURCE_VERSION = 123;

    private static List<AssertionError> validationErrors;

    private int resourceVersion = INITIAL_RESOURCE_VERSION;
    private final static ClientHolder clientHolder = createTestClient();
    private List<Memento> mementos = new ArrayList<>();

    // create a client to manipulate during this test to avoid recycling it and breaking other tests
    private static ClientHolder createTestClient() {
        Memento memento = TestUtils.silenceOperatorLogger();
        try {
            return ClientHelper.getInstance().take();
        } finally {
            memento.revert();
        }
    }

    @Before
    public void setUp() throws Exception {
        mementos.add(TestUtils.silenceOperatorLogger());
        mementos.add(TestServerWatchFactory.install(getHostPath()));
        validationErrors = new ArrayList<>();
    }

    @After
    public void tearDown() throws Exception {
        for (Memento memento : mementos) memento.revert();
        if (!validationErrors.isEmpty()) throw validationErrors.get(0);
    }

    @Test
    public void whenDomainWatchReceivesAddResponse_returnItFromIterator() throws Exception {
        Domain domain = new Domain().apiVersion(API_VERSION).kind("Domain").metadata(createMetaData("domain1", NAMESPACE));
        defineHttpResponse(DOMAIN_RESOURCE, withResponses(createAddedResponse(domain)));

        WatchI<Domain> domainWatch = new WatchBuilder(clientHolder).createDomainWatch(NAMESPACE);

        assertThat(domainWatch, contains(addEvent(domain)));
    }

    @Test
    public void whenDomainWatchReceivesModifyAndDeleteResponses_returnBothFromIterator() throws Exception {
        Domain domain1 = new Domain().apiVersion(API_VERSION).kind("Domain").metadata(createMetaData("domain1", NAMESPACE));
        Domain domain2 = new Domain().apiVersion(API_VERSION).kind("Domain").metadata(createMetaData("domain2", NAMESPACE));
        defineHttpResponse(DOMAIN_RESOURCE, withResponses(createModifiedResponse(domain1), createDeletedResponse(domain2)));

        WatchI<Domain> domainWatch = new WatchBuilder(clientHolder).createDomainWatch(NAMESPACE);

        assertThat(domainWatch, contains(modifyEvent(domain1), deleteEvent(domain2)));
    }

    @Test
    public void whenDomainWatchReceivesErrorResponse_returnItFromIterator() throws Exception {
        defineHttpResponse(DOMAIN_RESOURCE, withResponses(createErrorResponse(HTTP_ENTITY_TOO_LARGE)));

        WatchI<Domain> domainWatch = new WatchBuilder(clientHolder).createDomainWatch(NAMESPACE);

        assertThat(domainWatch, contains(errorEvent(HTTP_ENTITY_TOO_LARGE)));
    }

    @Test
    public void whenServiceWatchSpecifiesParameters_verifyAndReturnResponse() throws Exception {
        String startResourceVersion = getNextResourceVersion();
        V1Service service = new V1Service().apiVersion(API_VERSION).kind("Service").metadata(createMetaData("service3", NAMESPACE));
        defineHttpResponse(SERVICE_RESOURCE, withResponses(createModifiedResponse(service))
                                            .andValidations(
                                                    parameter("resourceVersion").withValue(startResourceVersion),
                                                    parameter("labelSelector").withValue(DOMAINUID_LABEL),
                                                    parameter("watch").withValue("true")));

        WatchI<V1Service> serviceWatch = new WatchBuilder(clientHolder)
                                            .withResourceVersion(startResourceVersion)
                                            .withLabelSelector(DOMAINUID_LABEL)
                                          .createServiceWatch(NAMESPACE);

        assertThat(serviceWatch, contains(modifyEvent(service)));
    }

    @Test
    public void whenPodWatchSpecifiesParameters_verifyAndReturnResponse() throws Exception {
        V1Pod pod = new V1Pod().apiVersion(API_VERSION).kind("Pod").metadata(createMetaData("pod4", NAMESPACE));
        defineHttpResponse(POD_RESOURCE, withResponses(createAddedResponse(pod))
                                         .andValidations(
                                                parameter("fieldSelector").withValue("thisValue"),
                                                parameter("includeUninitialized").withValue("false"),
                                                parameter("limit").withValue("25")));

        WatchI<V1Pod> podWatch = new WatchBuilder(clientHolder)
                                            .withFieldSelector("thisValue")
                                            .withIncludeUninitialized(false)
                                            .withLimit(25)
                                          .createPodWatch(NAMESPACE);

        assertThat(podWatch, contains(addEvent(pod)));
    }

    @Test
    public void whenPodWatchFindsNoData_hasNextReturnsFalse() throws Exception {
        defineHttpResponse(POD_RESOURCE, NO_RESPONSES);

        WatchI<V1Pod> podWatch = new WatchBuilder(clientHolder).createPodWatch(NAMESPACE);

        assertThat(podWatch.hasNext(), is(false));
    }

    @Test
    public void whenIngressWatchSpecifiesParameters_verifyAndReturnResponse() throws Exception {
        V1beta1Ingress ingress = new V1beta1Ingress().apiVersion(API_VERSION).kind("Ingress").metadata(createMetaData("ingress", NAMESPACE));
        defineHttpResponse(INGRESS_RESOURCE, withResponses(createDeletedResponse(ingress))
                                         .andValidations(
                                                parameter("pretty").withValue("true"),
                                                parameter("timeoutSeconds").withValue("15"),
                                                parameter("limit").withValue("500")));

        WatchI<V1beta1Ingress> ingressWatch = new WatchBuilder(clientHolder)
                                                .withPrettyPrinting()
                                                .withTimeoutSeconds(15)
                                              .createIngressWatch(NAMESPACE);

        assertThat(ingressWatch, contains(deleteEvent(ingress)));
    }

    private void defineHttpResponse(String resourceName, JsonServletAction... responses) {
        defineResource(resourceName, new JsonServlet(responses));
    }


    static class ParameterValidation {
        private String parameterName;
        private String expectedValue;

        private ParameterValidation(String parameterName) {
            this.parameterName = parameterName;
        }

        static ParameterValidation parameter(String parameterName) {
            return new ParameterValidation(parameterName);
        }

        ParameterValidation withValue(String expectedValue) {
            this.expectedValue = expectedValue;
            return this;
        }

        void verify(String[] parameterValues) {
            try {
                assertThat("parameter " + parameterName, getSingleValue(parameterValues), equalTo(expectedValue));
            } catch (AssertionError e) {
                validationErrors.add(e);
            }
        }
    }

    private static String getSingleValue(String[] values) {
        if (values == null || values.length == 0)
            return null;
        else
            return values[0];
    }

    static class JsonServletAction {
        private ParameterValidation[] validations = new ParameterValidation[0];
        private WebResource webResource;

        private JsonServletAction(String... responses) {
            webResource = new WebResource(String.join(EOL, responses), "application/json");
        }

        static JsonServletAction withResponses(String... responses) {
            return new JsonServletAction(responses);
        }

        JsonServletAction andValidations(ParameterValidation... validations) {
            this.validations = validations;
            return this;
        }
    }

    private final static JsonServletAction NO_RESPONSES = new JsonServletAction();

    static class JsonServlet extends PseudoServlet {
        private List<JsonServletAction> actions;
        int requestNum = 0;

        private JsonServlet(JsonServletAction... actions) {
            this.actions = new ArrayList<>(Arrays.asList(actions));
        }

        @Override
        public WebResource getGetResponse() throws IOException {
            if (requestNum >= actions.size()) return new WebResource("Unexpected Request #" + requestNum, HTTP_UNAVAILABLE);

            JsonServletAction action = actions.get(requestNum++);
            for (ParameterValidation validation : action.validations)
                validation.verify(getParameter(validation.parameterName));

            return action.webResource;
        }

    }

    private V1ObjectMeta createMetaData(String name, String namespace) {
        return new V1ObjectMeta().name(name).namespace(namespace).resourceVersion(getNextResourceVersion());
    }

    private String getNextResourceVersion() {
        return Integer.toString(resourceVersion++);
    }

    private <T> String createAddedResponse(T object) {
        return WatchEvent.createAddedEvent(object).toJson();
    }

    private <T> String createModifiedResponse(T object) {
        return WatchEvent.createModifiedEvent(object).toJson();
    }

    private <T> String createDeletedResponse(T object) {
        return WatchEvent.createDeleteEvent(object).toJson();
    }

    private String createErrorResponse(int statusCode) {
        return WatchEvent.createErrorEvent(statusCode).toJson();
    }

    static class TestServerWatchFactory extends WatchBuilder.WatchFactoryImpl {
        static Memento install(String basePath) throws NoSuchFieldException {
            return StaticStubSupport.install(WatchBuilder.class, "FACTORY", new TestServerWatchFactory(basePath));
        }

        private String basePath;

        private TestServerWatchFactory(String basePath) {
            this.basePath = basePath;
        }

        @Override
        public <T> WatchI<T> createWatch(ClientHolder clientHolder, CallParams callParams, Class<?> responseBodyType, BiFunction<ClientHolder, CallParams, Call> function) throws ApiException {
            clientHolder.getApiClient().setBasePath(basePath);
            return super.createWatch(clientHolder, callParams, responseBodyType, function);
        }
    }
}

