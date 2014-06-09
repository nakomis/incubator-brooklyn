package brooklyn.event.feed.ssh;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import brooklyn.event.AttributeSensor;
import brooklyn.event.feed.PollConfig;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

public class SshPollConfig<T> extends PollConfig<SshPollValue, T, SshPollConfig<T>> {

    private Supplier<String> commandSupplier;
    private List<Supplier<Map<String,String>>> dynamicEnvironmentSupplier = MutableList.of();

    public static final Predicate<SshPollValue> DEFAULT_SUCCESS = new Predicate<SshPollValue>() {
        @Override
        public boolean apply(@Nullable SshPollValue input) {
            return input != null && input.getExitStatus() == 0;
        }};

    public SshPollConfig(AttributeSensor<T> sensor) {
        super(sensor);
        super.checkSuccess(DEFAULT_SUCCESS);
    }

    public SshPollConfig(SshPollConfig<T> other) {
        super(other);
        commandSupplier = other.commandSupplier;
    }
    
    /** @deprecated since 0.7.0; use {@link #getCommandSupplier()} and resolve just-in-time */
    public String getCommand() {
        return getCommandSupplier().get();
    }
    public Supplier<String> getCommandSupplier() {
        return commandSupplier;
    }
    
    /** @deprecated since 0.7.0; use {@link #getEnvSupplier()} and resolve just-in-time */
    public Map<String, String> getEnv() {
        return getEnvSupplier().get();
    }
    public Supplier<Map<String,String>> getEnvSupplier() {
        return new Supplier<Map<String,String>>() {
            @Override
            public Map<String, String> get() {
                Map<String,String> result = MutableMap.of();
                for (Supplier<Map<String, String>> envS: dynamicEnvironmentSupplier) {
                    if (envS!=null) {
                        Map<String, String> envM = envS.get();
                        if (envM!=null) {
                            mergeEnvMaps(envM, result);
                        }
                    }
                }
                return result;
            }
        };
    }
    
    protected void mergeEnvMaps(Map<String,String> supplied, Map<String,String> target) {
        if (supplied==null) return;
        // as the value is a string there is no need to look at deep merge behaviour
        target.putAll(supplied);
    }

    public SshPollConfig<T> command(String val) { return command(Suppliers.ofInstance(val)); }
    public SshPollConfig<T> command(Supplier<String> val) {
        this.commandSupplier = val;
        return this;
    }

    /** add the given env param; sequence is as per {@link #env(Supplier)} */
    public SshPollConfig<T> env(String key, String val) {
        return env(Collections.singletonMap(key, val));
    }
    
    /** add the given env params; sequence is as per {@link #env(Supplier)} */
    public SshPollConfig<T> env(Map<String,String> val) {
        return env(Suppliers.ofInstance(val));
    }

    /** 
     * adds the given dynamic supplier of environment variables.
     * <p>
     * use of a supplier allows env vars to be computed on each execution,
     * for example to take the most recent sensor values.
     * <p>
     * in the case of multiple map suppliers, static maps, or static {@link #env(String, String)} 
     * key value pairs, the order in which they are specified here is the order
     * in which they are computed and applied. 
     **/
    public SshPollConfig<T> env(Supplier<Map<String,String>> val) {
        dynamicEnvironmentSupplier.add(val);
        return this;
    }

}
