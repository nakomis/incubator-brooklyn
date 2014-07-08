package brooklyn.entity.webapp.jboss;

import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.software.AbstractDockerLiveTest;
import brooklyn.location.Location;
import brooklyn.test.Asserts;
import brooklyn.test.HttpTestUtils;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.Test;

import java.net.URL;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertNotNull;

/**
 * A simple test of installing+running on Docker, using various OS distros and versions.
 */
public class Jboss7DockerLiveTest extends AbstractDockerLiveTest {

   private URL warUrl = checkNotNull(getClass().getClassLoader().getResource("brooklyn-example-hello-world-webapp.war"));

   @Override
   protected void doTest(Location loc) throws Exception {
      final JBoss7Server server = app.createAndManageChild(EntitySpec.create(JBoss7Server.class)
              .configure("war", warUrl.toString()));

      app.start(ImmutableList.of(loc));

      String url = server.getAttribute(JBoss7Server.ROOT_URL);

      HttpTestUtils.assertHttpStatusCodeEventuallyEquals(url, 200);
      HttpTestUtils.assertContentContainsText(url, "Hello");

      Asserts.succeedsEventually(new Runnable() {
         @Override
         public void run() {
            assertNotNull(server.getAttribute(JBoss7Server.REQUEST_COUNT));
            assertNotNull(server.getAttribute(JBoss7Server.ERROR_COUNT));
            assertNotNull(server.getAttribute(JBoss7Server.TOTAL_PROCESSING_TIME));
            assertNotNull(server.getAttribute(JBoss7Server.MAX_PROCESSING_TIME));
            assertNotNull(server.getAttribute(JBoss7Server.BYTES_RECEIVED));
            assertNotNull(server.getAttribute(JBoss7Server.BYTES_SENT));
         }
      });
   }

   @Test(enabled = false)
   public void testDummy() {
   } // Convince testng IDE integration that this really does have test methods

}
