package brooklyn.entity.nosql.couchbase;

import static java.lang.String.format;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import brooklyn.enricher.Enrichers;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.feed.http.HttpFeed;
import brooklyn.event.feed.http.HttpPollConfig;
import brooklyn.event.feed.http.HttpValueFunctions;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.location.cloud.CloudLocationConfig;
import brooklyn.util.collections.MutableSet;
import brooklyn.util.config.ConfigBag;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;

public class CouchbaseNodeImpl extends SoftwareProcessImpl implements CouchbaseNode {
    
    private volatile HttpFeed httpRunningFeed;
    private volatile HttpFeed httpInitializedFeed;
    private volatile HttpFeed httpServiceUpFeed;

    @Override
    public Class<CouchbaseNodeDriver> getDriverInterface() {
        return CouchbaseNodeDriver.class;
    }

    @Override
    public CouchbaseNodeDriver getDriver() {
        return (CouchbaseNodeDriver) super.getDriver();
    }

    @Override
    public void init() {
        super.init();
        
        subscribe(this, HTTP_SERVER_RUNNING, new SensorEventListener<Boolean>() {
            @Override
            public void onEvent(SensorEvent<Boolean> booleanSensorEvent) {
                if (Boolean.TRUE.equals(booleanSensorEvent.getValue()) && !getAttribute(CLUSTER_INITIALIZED)) {
                    String hostname = getAttribute(HOSTNAME);
                    String webPort = getConfig(CouchbaseNode.COUCHBASE_WEB_ADMIN_PORT).iterator().next().toString();
                    setAttribute(CouchbaseNode.COUCHBASE_WEB_ADMIN_URL, format("http://%s:%s", hostname, webPort));
                    if (!getConfig(REQUIRES_CLUSTER)) {
                        // If it's not going to be a in a 'real' cluster, make it a standalone cluster 
                        getDriver().initializeCluster();
                    }
                }
            }
        });

    }

    protected Map<String, Object> obtainProvisioningFlags(@SuppressWarnings("rawtypes") MachineProvisioningLocation location) {
        ConfigBag result = ConfigBag.newInstance(super.obtainProvisioningFlags(location));
        result.configure(CloudLocationConfig.OS_64_BIT, true);
        return result.getAllConfig();
    }

    @Override
    protected Collection<Integer> getRequiredOpenPorts() {
        // TODO this creates a huge list of inbound ports; much better to define on a security group using range syntax!
        int erlangRangeStart = getConfig(NODE_DATA_EXCHANGE_PORT_RANGE_START).iterator().next();
        int erlangRangeEnd = getConfig(NODE_DATA_EXCHANGE_PORT_RANGE_END).iterator().next();

        Set<Integer> newPorts = MutableSet.<Integer>copyOf(super.getRequiredOpenPorts());
        newPorts.remove(erlangRangeStart);
        newPorts.remove(erlangRangeEnd);
        for (int i = erlangRangeStart; i <= erlangRangeEnd; i++)
            newPorts.add(i);
        return newPorts;
    }

    @Override
    public void serverAdd(String serverToAdd, String username, String password) {
        getDriver().serverAdd(serverToAdd, username, password);
    }

    @Override
    public void rebalance() {
        getDriver().rebalance();
    }


    public void connectSensors() {
        super.connectSensors();
        HostAndPort hostAndPort = BrooklynAccessUtils.getBrooklynAccessibleAddress(this,
                getAttribute(COUCHBASE_WEB_ADMIN_PORT));
        String managementUri = String.format("http://%s:%s",
                hostAndPort.getHostText(), hostAndPort.getPort());
        setAttribute(COUCHBASE_WEB_ADMIN_URL, managementUri);
        httpRunningFeed = HttpFeed.builder()
                .entity(this)
                .period(1000)
                .baseUri(managementUri)
                .credentials(getConfig(COUCHBASE_ADMIN_USERNAME), getConfig(COUCHBASE_ADMIN_PASSWORD))
                .poll(new HttpPollConfig<Boolean>(HTTP_SERVER_RUNNING)
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onFailureOrException(Functions.constant(false)))
                .build();
        httpInitializedFeed = HttpFeed.builder()
                .entity(this)
                .period(1000)
                .baseUri(managementUri + "/pools/nodes")
                .credentials(getConfig(COUCHBASE_ADMIN_USERNAME), getConfig(COUCHBASE_ADMIN_PASSWORD))
                .poll(new HttpPollConfig<Boolean>(CLUSTER_INITIALIZED)
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onFailureOrException(Functions.constant(false)))
                .build();
        connectServiceUpIsRunning();
    }
    
    @Override
    protected void connectServiceUpIsRunning() {
        if (getConfig(REQUIRES_CLUSTER)) {
            httpServiceUpFeed = HttpFeed.builder()
                    .build();
        } else {
            addEnricher(
                Enrichers.builder()
                    .propagating(ImmutableMap.of(CLUSTER_INITIALIZED, SERVICE_UP))
                    .build()
            );
        }
    }

    public void disconnectSensors() {
        super.disconnectSensors();
        
        if (httpRunningFeed != null) {
            httpRunningFeed.stop();
        }
        
        if (httpInitializedFeed != null) {
            httpInitializedFeed.stop();
        }
        
        disconnectServiceUpIsRunning();
    }

    @Override
    protected void disconnectServiceUpIsRunning() {
        if (httpServiceUpFeed != null) {
            httpServiceUpFeed.stop();
        }
    }

    @Override
    public void initializeCluster() {
        getDriver().initializeCluster();
    }
}
