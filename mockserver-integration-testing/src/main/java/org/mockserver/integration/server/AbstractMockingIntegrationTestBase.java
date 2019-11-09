package org.mockserver.integration.server;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.mockserver.client.MockServerClient;
import org.mockserver.client.NettyHttpClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.model.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.Header.header;

/**
 * @author jamesdbloom
 */
public abstract class AbstractMockingIntegrationTestBase {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(AbstractMockingIntegrationTestBase.class);
    protected static MockServerClient mockServerClient;
    protected static String servletContext = "";
    protected static List<String> headersToIgnore = ImmutableList.of(
        HttpHeaderNames.SERVER.toString(),
        HttpHeaderNames.EXPIRES.toString(),
        HttpHeaderNames.DATE.toString(),
        HttpHeaderNames.HOST.toString(),
        HttpHeaderNames.CONNECTION.toString(),
        HttpHeaderNames.USER_AGENT.toString(),
        HttpHeaderNames.CONTENT_LENGTH.toString(),
        HttpHeaderNames.ACCEPT_ENCODING.toString(),
        HttpHeaderNames.TRANSFER_ENCODING.toString(),
        HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(),
        HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS.toString(),
        HttpHeaderNames.VARY.toString(),
        HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS.toString(),
        HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS.toString(),
        HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS.toString(),
        HttpHeaderNames.ACCESS_CONTROL_MAX_AGE.toString(),
        "version",
        "x-cors"
    );

    protected static EchoServer insecureEchoServer;
    protected static EchoServer secureEchoServer;

    @BeforeClass
    public static void startEchoServer() {
        if (insecureEchoServer == null) {
            insecureEchoServer = new EchoServer(false);
        }
        if (secureEchoServer == null) {
            secureEchoServer = new EchoServer(true);
        }
    }

    @BeforeClass
    public static void resetServletContext() {
        servletContext = "";
    }

    public abstract int getServerPort();

    public int getServerSecurePort() {
        return getServerPort();
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    protected String calculatePath(String path) {
        return (!path.startsWith("/") ? "/" : "") + path;
    }

    private static EventLoopGroup clientEventLoopGroup;
    static NettyHttpClient httpClient;

    @BeforeClass
    public static void createClientAndEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup();
        httpClient = new NettyHttpClient(new MockServerLogger(), clientEventLoopGroup, null);
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    String addContextToPath(String path) {
        String cleanedPath = path;
        if (isNotBlank(servletContext)) {
            cleanedPath =
                (!servletContext.startsWith("/") ? "/" : "") +
                    servletContext +
                    (!servletContext.endsWith("/") ? "/" : "") +
                    (cleanedPath.startsWith("/") ? cleanedPath.substring(1) : cleanedPath);
        }
        return (!cleanedPath.startsWith("/") ? "/" : "") + cleanedPath;
    }

    protected void verifyRequestsMatches(HttpRequest[] httpRequests, HttpRequest... httpRequestMatchers) {
        if (httpRequests.length != httpRequestMatchers.length) {
            throw new AssertionError("Number of request matchers does not match number of requests, expected:<" + httpRequestMatchers.length + "> but was:<" + httpRequests.length + ">");
        } else {
            for (int i = 0; i < httpRequestMatchers.length; i++) {
                if (!new HttpRequestMatcher(MOCK_SERVER_LOGGER, httpRequestMatchers[i]).matches(null, httpRequests[i])) {
                    throw new AssertionError("Request does not match request matcher, expected:<" + httpRequestMatchers[i] + "> but was:<" + httpRequests[i] + ">");
                }
            }
        }
    }

    protected HttpResponse makeRequest(HttpRequest httpRequest, Collection<String> headersToIgnore) {
        try {
            boolean isSsl = httpRequest.isSecure() != null && httpRequest.isSecure();
            int port = (isSsl ? getServerSecurePort() : getServerPort());
            httpRequest.withPath(addContextToPath(httpRequest.getPath().getValue()));
            httpRequest.withHeader(HOST.toString(), "localhost:" + port);
            boolean isDebug = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0;
            HttpResponse httpResponse = httpClient.sendRequest(httpRequest, new InetSocketAddress("localhost", port))
                .get(30, (isDebug ? TimeUnit.MINUTES : TimeUnit.SECONDS));
            Headers headers = new Headers();
            for (Header header : httpResponse.getHeaderList()) {
                if (!headersToIgnore.contains(header.getName().getValue().toLowerCase())) {
                    if (header.getName().getValue().equalsIgnoreCase(CONTENT_TYPE.toString())) {
                        // this fixes Tomcat which removes the space between
                        // media type and charset in the Content-Type header
                        for (NottableString value : new ArrayList<NottableString>(header.getValues())) {
                            header.getValues().clear();
                            header.addValues(value.getValue().replace(";charset", "; charset"));
                        }
                        header = header(header.getName().lowercase(), header.getValues());
                    }
                    headers.withEntry(header);
                }
            }
            httpResponse.withHeaders(headers);
            return httpResponse;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
