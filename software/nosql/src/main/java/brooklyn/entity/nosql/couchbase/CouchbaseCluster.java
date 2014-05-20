package brooklyn.entity.nosql.couchbase;

import java.util.List;
import java.util.Set;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.util.flags.SetFromFlag;

import com.google.common.reflect.TypeToken;

@ImplementedBy(CouchbaseClusterImpl.class)
public interface CouchbaseCluster extends DynamicCluster {

    AttributeSensor<Integer> ACTUAL_CLUSTER_SIZE = Sensors.newIntegerSensor("coucbase.cluster.actualClusterSize", "returns the actual number of nodes in the cluster");

    @SuppressWarnings("serial")
    AttributeSensor<Set<CouchbaseNode>> COUCHBASE_CLUSTER_UP_NODES = Sensors.newSensor(new TypeToken<Set<CouchbaseNode>>() {}, 
            "couchbase.cluster.clusterEntities", "the set of service up nodes");
    
    @SuppressWarnings("serial")
    AttributeSensor<Set<CouchbaseNode>> COUCHBASE_CLUSTER_RUNNING_NODES = Sensors.newSensor(new TypeToken<Set<CouchbaseNode>>() {}, 
            "couchbase.cluster.runningEntities", "all running nodes, including nodes that have not yet been added to the cluster");

    @SuppressWarnings("serial")
    AttributeSensor<List<String>> COUCHBASE_CLUSTER_BUCKETS = Sensors.newSensor(new TypeToken<List<String>>() {}, 
            "couchbase.cluster.buckets", "Names of all the buckets the couchbase cluster");

    AttributeSensor<CouchbaseNode> COUCHBASE_PRIMARY_NODE = Sensors.newSensor(CouchbaseNode.class, "couchbase.cluster.primaryNode", "The primary couchbase node to query and issue add-server and rebalance on");

    AttributeSensor<Boolean> IS_CLUSTER_INITIALIZED = Sensors.newBooleanSensor("couchbase.cluster.isClusterInitialized", "flag to emit if the couchbase cluster was intialized");

    @SetFromFlag("intialQuorumSize")
    ConfigKey<Integer> INITIAL_QUORUM_SIZE = ConfigKeys.newIntegerConfigKey("couchbase.cluster.intialQuorumSize", "Initial cluster quorum size - number of initial nodes that must have been successfully started to report success (if < 0, then use value of INITIAL_SIZE)",
            -1);

}
