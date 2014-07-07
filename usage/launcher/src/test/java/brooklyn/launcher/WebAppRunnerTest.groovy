package brooklyn.launcher

import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

import brooklyn.BrooklynVersion;
import brooklyn.config.BrooklynProperties
import brooklyn.entity.basic.Entities
import brooklyn.management.internal.LocalManagementContext
import brooklyn.test.HttpTestUtils
import brooklyn.util.internal.TimeExtras
import brooklyn.util.net.Networking
import brooklyn.util.time.Duration

import com.google.common.collect.Lists


/**
 * These tests require the brooklyn.war to work. (Should be placed by maven build.)
 */
public class WebAppRunnerTest {
    static { TimeExtras.init() }

    public static final Logger log = LoggerFactory.getLogger(WebAppRunnerTest.class);
    
    public static final String WAR_PATH = "classpath://brooklyn-example-hello-world-webapp.war";
            
    private static Duration TIMEOUT_MS;
    static { TIMEOUT_MS = Duration.THIRTY_SECONDS; }
    
    List<LocalManagementContext> managementContexts = Lists.newCopyOnWriteArrayList();
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        for (LocalManagementContext managementContext : managementContexts) {
            Entities.destroyAll(managementContext);
        }
        managementContexts.clear();
    }
    
    LocalManagementContext newManagementContext(BrooklynProperties brooklynProperties) {
        LocalManagementContext result = new LocalManagementContext(brooklynProperties);
        managementContexts.add(result);
        return result;
    }
    
    BrooklynWebServer createWebServer(Map properties) {
        Map bigProps = [:] + properties;
        Map attributes = bigProps.attributes
        if (attributes==null) {
            attributes = [:]
        } else {
            attributes = [:] + attributes; //copy map, don't change what was supplied
        }
        bigProps.attributes = attributes;

        BrooklynProperties brooklynProperties = BrooklynProperties.Factory.newDefault();
        brooklynProperties.putAll(bigProps);
        brooklynProperties.put("brooklyn.webconsole.security.provider","brooklyn.rest.security.provider.AnyoneSecurityProvider")
        brooklynProperties.put("brooklyn.webconsole.security.https.required","false");
        return new BrooklynWebServer(bigProps, newManagementContext(brooklynProperties));
    }
    
    @Test
    public void testStartWar1() {
        if (!Networking.isPortAvailable(8090))
            fail("Another process is using port 8090 which is required for this test.");
        BrooklynWebServer server = createWebServer(port:8090);
        assertNotNull(server);
        
        try {
            server.start();
            assertBrooklynEventuallyAt("http://localhost:8090/");
        } finally {
            server.stop();
        }
    }

    public static void assertBrooklynEventuallyAt(String url) {
        HttpTestUtils.assertContentEventuallyContainsText(url, "Brooklyn Web Console");
    }
    
    @Test
    public void testStartSecondaryWar() {
        if (!Networking.isPortAvailable(8090))
            fail("Another process is using port 8090 which is required for this test.");
        BrooklynWebServer server = createWebServer(port:8090, war:"brooklyn.war", wars:["hello":WAR_PATH]);
        assertNotNull(server);
        
        try {
            server.start();

            assertBrooklynEventuallyAt("http://localhost:8090/");
            HttpTestUtils.assertContentEventuallyContainsText("http://localhost:8090/hello",
                "This is the home page for a sample application");

        } finally {
            server.stop();
        }
    }

    @Test
    public void testStartSecondaryWarAfter() {
        if (!Networking.isPortAvailable(8090))
            fail("Another process is using port 8090 which is required for this test.");
        BrooklynWebServer server = createWebServer(port:8090, war:"brooklyn.war");
        assertNotNull(server);
        
        try {
            server.start();
            server.deploy("/hello", WAR_PATH);

            assertBrooklynEventuallyAt("http://localhost:8090/");
            HttpTestUtils.assertContentEventuallyContainsText("http://localhost:8090/hello",
                "This is the home page for a sample application");

        } finally {
            server.stop();
        }
    }

    @Test
    public void testStartWithLauncher() {
        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                .brooklynProperties("brooklyn.webconsole.security.provider",'brooklyn.rest.security.provider.AnyoneSecurityProvider')
                .webapp("/hello", WAR_PATH)
                .start();
        BrooklynServerDetails details = launcher.getServerDetails();
        
        try {
            details.getWebServer().deploy("/hello2", WAR_PATH);

            assertBrooklynEventuallyAt(details.getWebServerUrl());
            HttpTestUtils.assertContentEventuallyContainsText(details.getWebServerUrl()+"hello", "This is the home page for a sample application");
            HttpTestUtils.assertContentEventuallyContainsText(details.getWebServerUrl()+"hello2", "This is the home page for a sample application");
            HttpTestUtils.assertHttpStatusCodeEventuallyEquals(details.getWebServerUrl()+"hello0", 404);

        } finally {
            details.getWebServer().stop();
        }
    }
    
}
