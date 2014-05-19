package brooklyn.entity.nosql.couchbase;

import brooklyn.entity.basic.SoftwareProcessDriver;

public interface CouchbaseNodeDriver extends SoftwareProcessDriver {
    public String getOsTag();

    public void initializeCluster();
    
    public void serverAdd(String serverToAdd, String username, String password);

    public void rebalance();

}
