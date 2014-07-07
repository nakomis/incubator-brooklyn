package brooklyn.entity.proxy.nginx

import static brooklyn.test.TestUtils.*
import static org.testng.Assert.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.basic.ApplicationBuilder
import brooklyn.entity.basic.Entities
import brooklyn.entity.group.DynamicCluster
import brooklyn.entity.proxying.EntitySpec
import brooklyn.entity.webapp.JavaWebAppService
import brooklyn.entity.webapp.WebAppService
import brooklyn.entity.webapp.jboss.JBoss7Server
import brooklyn.location.Location
import brooklyn.location.MachineLocation
import brooklyn.test.Asserts
import brooklyn.test.HttpTestUtils
import brooklyn.test.entity.TestApplication

/**
 * Test Nginx proxying a cluster of JBoss7Server entities on AWS for ENGR-1689.
 *
 * This test is a proof-of-concept for the Brooklyn demo application, with each
 * service running on a separate Amazon EC2 instance.
 */
public class NginxWebClusterEc2LiveTest {
    private static final Logger LOG = LoggerFactory.getLogger(NginxWebClusterEc2LiveTest.class)
    
    private TestApplication app
    private NginxController nginx
    private DynamicCluster cluster
    private Location loc

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        loc = app.managementContext.getLocationRegistry().resolve("aws-ec2:us-east-1")
    }

    @AfterMethod(alwaysRun = true)
    public void shutdown() {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }
    
    @Test(groups = "Live")
    public void testProvisionAwsCluster() {
        URL war = getClass().getClassLoader().getResource("brooklyn-example-hello-world-webapp.war")
        assertNotNull war, "Unable to locate resource $war"
        
        cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(JBoss7Server.class))
                .configure("initialSize", 2)
                .configure("httpPort", 8080)
                .configure(JavaWebAppService.ROOT_WAR, war.path));
        
        nginx = app.createAndManageChild(EntitySpec.create(NginxController.class)
                .configure("cluster", cluster)
                .configure("domain", "localhost")
                .configure("port", 8000)
                .configure("portNumberSensor", WebAppService.HTTP_PORT));

        app.start([ loc ])
        
        Asserts.succeedsEventually {
            // Nginx URL is available
            MachineLocation machine = nginx.locations.find { true }
            String url = "http://" + machine.address.hostName + ":" + nginx.getAttribute(NginxController.PROXY_HTTP_PORT)
                    HttpTestUtils.assertHttpStatusCodeEquals(url, 200)
            
                    // Web-app URL is available
                    cluster.members.each {
                HttpTestUtils.assertHttpStatusCodeEquals(it.getAttribute(JavaWebAppService.ROOT_URL), 200)
            }
        }

		nginx.stop()
    }
}
