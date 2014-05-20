package brooklyn.entity.nosql.couchbase;

import static brooklyn.util.JavaGroovyEquivalents.groovyTruth;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import brooklyn.entity.group.DynamicClusterImpl;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.location.Location;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.task.TaskBuilder;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class CouchbaseClusterImpl extends DynamicClusterImpl implements CouchbaseCluster {
    private static final Logger LOG = LoggerFactory.getLogger(CouchbaseClusterImpl.class);
    private final Object couchbaseMutex = new Object[0];

    public void init() {
        super.init();
       
        Map<String, String> flags = MutableMap.of("name", "Controller targets tracker");
        
        AbstractMembershipTrackingPolicy serverPoolMemberTrackerPolicy = new AbstractMembershipTrackingPolicy(flags) {
            // All calls to [get|set]Members() are synchronized on members. Attempting to call getMembers() in 
            // onServerPoolMemberChanged below causes a deadlock
            protected void onEntityChange(final Entity member) {
                Entities.submit(CouchbaseClusterImpl.this, TaskBuilder.builder().name("cluster-entity-changed").body(new Runnable() {
                    @Override public void run() {
                        onServerPoolMemberChanged(member);
                    }
                }).build());
            }
            protected void onEntityAdded(final Entity member) {
                Entities.submit(CouchbaseClusterImpl.this, TaskBuilder.builder().name("cluster-entity-added").body(new Runnable() {
                    @Override public void run() {
                        onServerPoolMemberChanged(member);
                    }
                }).build());
            }
            protected void onEntityRemoved(final Entity member) {
                Entities.submit(CouchbaseClusterImpl.this, TaskBuilder.builder().name("cluster-entity-removed").body(new Runnable() {
                    @Override public void run() {
                        onServerPoolMemberChanged(member);
                    }
                }).build());
            }
        };
        
        serverPoolMemberTrackerPolicy.setConfig(AbstractMembershipTrackingPolicy.NOTIFY_ON_DUPLICATES, false);
        serverPoolMemberTrackerPolicy.setConfig(AbstractMembershipTrackingPolicy.SENSORS_TO_TRACK, 
                ImmutableSet.<Sensor<?>>of(CouchbaseNode.HTTP_SERVER_RUNNING, Attributes.SERVICE_UP, CouchbaseNode.CLUSTER_INITIALIZED));
        
        addPolicy(serverPoolMemberTrackerPolicy);
        serverPoolMemberTrackerPolicy.setGroup(this);
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        super.start(locations);
        connectSensors();
    }

    @Override
    public void stop() {
        super.stop();
    }

    public void connectSensors() {
        subscribeToMembers(this, SERVICE_UP, new SensorEventListener<Boolean>() {
            @Override public void onEvent(SensorEvent<Boolean> event) {
                setAttribute(SERVICE_UP, calculateServiceUp());
            }
        });
    }

    protected synchronized void onServerPoolMemberChanged(Entity member) {
        synchronized (couchbaseMutex) {
            Collection<Entity> members = ImmutableSet.copyOf(this.getMembers());
            final CouchbaseNode primaryNode = getPrimaryNode();
            if (primaryNode == null) {
                Optional<Entity> runningNode = Iterables.tryFind(members, new Predicate<Entity>() {
                    @Override public boolean apply(Entity input) {
                        return input instanceof CouchbaseNode && Boolean.TRUE.equals(input.getAttribute(CouchbaseNode.HTTP_SERVER_RUNNING));
                    }
                });
                if (runningNode.isPresent()) {
                    CouchbaseNode newPrimary = (CouchbaseNode)runningNode.get();
                    LOG.debug("--=------------------------------------------ debug primary");
                    LOG.info("primary node node set, choosing {} as primary", newPrimary);
                    setPrimaryNode(newPrimary);
                    Entities.invokeEffector(this, runningNode.get(), CouchbaseNode.INITIALIZE_CLUSTER);
                    return; // Will need to wait until the cluster is initialized
                } else {
                    LOG.debug("primary node not set, and no suitable candidates available, defering choice");
                    // There are no suitable candidates for primary node, so bail out and wait for one to become available
                    return;
                }
            } else if (!primaryNode.getAttribute(CouchbaseNode.CLUSTER_INITIALIZED)) {
                LOG.debug("primary node set, cluster not yet initialized, deferrring cluster operations");
                return; // Primary has been selected, but has not yet been initialized
                // TODO: If primary is not running, demote and choose different primary
            } else {
                // Primary node has been selected, and cluster is initialized
                // TODO: If primary is not running, demote and choose different primary
                getUpNodes().add(primaryNode);
                
                Iterable<CouchbaseNode> nodeMembers = Iterables.transform(getMembers(), new Function<Entity, CouchbaseNode>() {
                    @Override public CouchbaseNode apply(Entity entity) {
                        return (CouchbaseNode)entity;
                    }
                });
                Set<CouchbaseNode> nodesToAdd = ImmutableSet.copyOf(Iterables.filter(nodeMembers, new Predicate<CouchbaseNode>() {
                    @Override public boolean apply(CouchbaseNode input) {
                        return !getUpNodes().contains(input) && input.getAttribute(CouchbaseNode.HTTP_SERVER_RUNNING);
                    }
                }));
                
                Set<CouchbaseNode> nodesToRemove = ImmutableSet.copyOf(Iterables.filter(getUpNodes(), new Predicate<CouchbaseNode>() {
                    @Override public boolean apply(CouchbaseNode input) {
                        return !(getMembers().contains(input)) || ((getMembers().contains(input)) && !input.getAttribute(CouchbaseNode.HTTP_SERVER_RUNNING));
                    }
                }));
                
                if (nodesToAdd.size() > 0) {
                    addServers(nodesToAdd);
                }
                if (nodesToRemove.size() > 0) {
                    removeServers(nodesToRemove);
                }
                
                // Removing nodes automatically triggers a re-balance
                if (nodesToAdd.size() > 0 && nodesToRemove.size() == 0) {
                    Entities.invokeEffector(this, primaryNode, CouchbaseNode.REBALANCE);
                }
                
            }
        }
    }
    
    protected boolean belongsInServerPool(Entity member) {
        if (!groovyTruth(member.getAttribute(Startable.SERVICE_UP))) {
            if (LOG.isTraceEnabled()) LOG.trace("Members of {}, checking {}, eliminating because not up", this, member);
            return false;
        }
        if (!getMembers().contains(member)) {
            if (LOG.isTraceEnabled())
                LOG.trace("Members of {}, checking {}, eliminating because not member", this, member);

            return false;
        }
        if (LOG.isTraceEnabled()) LOG.trace("Members of {}, checking {}, approving", this, member);

        return true;
    }


    protected EntitySpec<?> getMemberSpec() {
        EntitySpec<?> result = super.getMemberSpec();
        if (result == null) { 
            return EntitySpec.create(CouchbaseNode.class);
        }
        return result.configure(CouchbaseNode.REQUIRES_CLUSTER, true);
    }

    protected int getQuorumSize() {
        Integer quorumSize = getConfig(CouchbaseCluster.INITIAL_QUORUM_SIZE);
        if (quorumSize != null && quorumSize > 0)
            return quorumSize;
        // by default the quorum would be floor(initial_cluster_size/2) + 1
        return (int) Math.floor(getConfig(INITIAL_SIZE) / 2) + 1;
    }

    protected int getActualSize() {
        return Optional.fromNullable(getAttribute(CouchbaseCluster.ACTUAL_CLUSTER_SIZE)).or(-1);
    }

    private Set<CouchbaseNode> getUpNodes() {
        Set<CouchbaseNode> nodes = getAttribute(COUCHBASE_CLUSTER_UP_NODES);
        if (nodes == null) {
            nodes = Sets.newHashSet();
            setAttribute(COUCHBASE_CLUSTER_UP_NODES, nodes);
        }
        return nodes;
    }

    private CouchbaseNode getPrimaryNode() {
        return getAttribute(COUCHBASE_PRIMARY_NODE);
    }
    
    private void setPrimaryNode(CouchbaseNode primary) {
        CouchbaseNode currentPrimary = getAttribute(COUCHBASE_PRIMARY_NODE);
        if (currentPrimary != null && Boolean.TRUE.equals(((EntityLocal)currentPrimary).getAttribute(CouchbaseNode.IS_PRIMARY_NODE))) {
            ((EntityLocal)currentPrimary).setAttribute(CouchbaseNode.IS_PRIMARY_NODE, false);
        }
    
        ((EntityLocal)primary).setAttribute(CouchbaseNode.IS_PRIMARY_NODE, true);
        setAttribute(COUCHBASE_PRIMARY_NODE, primary);
    }

    @Override
    protected boolean calculateServiceUp() {
        if (!super.calculateServiceUp()) return false;
        Set<CouchbaseNode> upNodes = getAttribute(COUCHBASE_CLUSTER_UP_NODES);
        if (upNodes == null || upNodes.isEmpty() || upNodes.size() < getQuorumSize()) return false;
        return true;
    }

    protected void addServers(Set<CouchbaseNode> serversToAdd) {
        LOG.info("adding couchbase nodes to the cluster: {}", serversToAdd);

        for (CouchbaseNode e : serversToAdd) {
            addServer(e);
        }
    }

    protected void addServer(CouchbaseNode serverToAdd) {
        if (!isMemberInCluster(serverToAdd)) {
            getUpNodes().add(serverToAdd);
            String hostname = serverToAdd.getAttribute(Attributes.HOSTNAME) + ":" + serverToAdd.getConfig(CouchbaseNode.COUCHBASE_WEB_ADMIN_PORT).iterator().next();
            String username = serverToAdd.getConfig(CouchbaseNode.COUCHBASE_ADMIN_USERNAME);
            String password = serverToAdd.getConfig(CouchbaseNode.COUCHBASE_ADMIN_PASSWORD);

            Entities.invokeEffectorWithArgs(this, getPrimaryNode(), CouchbaseNode.SERVER_ADD, hostname, username, password);
            //FIXME check feedback of whether the server was added.
            ((EntityInternal) serverToAdd).setAttribute(CouchbaseNode.IS_IN_CLUSTER, true);
        }
    }

    private void removeServers(Set<CouchbaseNode> nodesToRemove) {
        LOG.info("removing couchbase nodes from the cluster: {}", nodesToRemove);
        for (CouchbaseNode node : nodesToRemove) {
            removeServer(node);
        }
    }
    
    private void removeServer(CouchbaseNode node) {
        String hostname = node.getAttribute(Attributes.HOSTNAME) + ":" + node.getConfig(CouchbaseNode.COUCHBASE_WEB_ADMIN_PORT).iterator().next();
        String username = node.getConfig(CouchbaseNode.COUCHBASE_ADMIN_USERNAME);
        String password = node.getConfig(CouchbaseNode.COUCHBASE_ADMIN_PASSWORD);

        Entities.invokeEffectorWithArgs(this, getPrimaryNode(), CouchbaseNode.SERVER_ADD, hostname, username, password);
        ((EntityInternal) node).setAttribute(CouchbaseNode.IS_IN_CLUSTER, true);
    }
    
    public boolean isClusterInitialized() {
        return Optional.fromNullable(getAttribute(IS_CLUSTER_INITIALIZED)).or(false);
    }

    public boolean isMemberInCluster(Entity e) {
        return Optional.fromNullable(e.getAttribute(CouchbaseNode.IS_IN_CLUSTER)).or(false);
    }
}
