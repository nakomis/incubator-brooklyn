package brooklyn.management.entitlement;

import java.net.URI;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.Application;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.BasicApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.location.Location;
import brooklyn.management.ManagementContext;
import brooklyn.management.entitlement.Entitlements.EntityAndItem;
import brooklyn.test.entity.LocalManagementContextForTests;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.exceptions.Exceptions;

public class AcmeEntitlementManagerTest {

    ManagementContext mgmt;
    Application app;
    ConfigBag configBag;
    
    public void setup(ConfigBag cfg) {
        mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newEmpty().addFrom(cfg));
        app = ApplicationBuilder.newManagedApp(EntitySpec.create(BasicApplication.class), mgmt);
    }

    @BeforeMethod(alwaysRun=true)
    public void init() {
        configBag = ConfigBag.newInstance();
        configBag.put(Entitlements.GLOBAL_ENTITLEMENT_MANAGER, AcmeEntitlementManager.class.getName());
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (app != null) Entities.destroyAll(app.getManagementContext());
        if (mgmt != null) Entities.destroyAll(mgmt);
        app = null;
        mgmt = null;
    }

    @Test
    public void testUserWithMinimalAllows() {
        setup(configBag);
        WebEntitlementContext entitlementContext = new WebEntitlementContext("bob", "127.0.0.1", URI.create("/applications").toString());
        Entitlements.setEntitlementContext(entitlementContext);
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.ROOT, null));
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.SEE_ENTITY, app));
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.INVOKE_EFFECTOR, EntityAndItem.of(app, "any-eff")));
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.SEE_SENSOR, EntityAndItem.of(app, "any-sensor")));
        // and can invoke methods, without any user/login registered
        confirmEffectorEntitlement(false);
    }

    @Test
    public void testUserWithReadOnlyAllows() {
        setup(configBag);
        WebEntitlementContext entitlementContext = new WebEntitlementContext("alice", "127.0.0.1", URI.create("/applications").toString());
        Entitlements.setEntitlementContext(entitlementContext);
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.ROOT, null));
        Assert.assertTrue(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.SEE_ENTITY, app));
        Assert.assertFalse(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.INVOKE_EFFECTOR, EntityAndItem.of(app, "any-eff")));
        Assert.assertTrue(Entitlements.isEntitled(mgmt.getEntitlementManager(), Entitlements.SEE_SENSOR, EntityAndItem.of(app, "any-sensor")));
        // and can invoke methods, without any user/login registered
        confirmEffectorEntitlement(false);
    }


    protected void confirmEffectorEntitlement(boolean shouldSucceed) {
        try {
            ((BasicApplication)app).start(ImmutableList.<Location>of());
            checkNoException(shouldSucceed);
        } catch (Exception e) {
            checkNotEntitledException(shouldSucceed, e);
        }
    }

    private void checkNoException(boolean shouldBeEntitled) {
        checkNotEntitledException(shouldBeEntitled, null);
    }

    private void checkNotEntitledException(boolean shouldBeEntitled, Exception e) {
        if (e==null) {
            if (shouldBeEntitled) return;
            Assert.fail("entitlement should have been denied");
        }
        
        Exception notEntitled = Exceptions.getFirstThrowableOfType(e, NotEntitledException.class);
        if (notEntitled==null)
            throw Exceptions.propagate(e);
        if (!shouldBeEntitled) {
            /* denied, as it should have been */ 
            return;
        }
        Assert.fail("entitlement should have been granted, but was denied: "+e);
    }

}
