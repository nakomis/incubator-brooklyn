/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.nosql.riak;

import static brooklyn.util.ssh.BashCommands.INSTALL_CURL;
import static brooklyn.util.ssh.BashCommands.INSTALL_TAR;
import static brooklyn.util.ssh.BashCommands.alternatives;
import static brooklyn.util.ssh.BashCommands.chainGroup;
import static brooklyn.util.ssh.BashCommands.commandToDownloadUrlAs;
import static brooklyn.util.ssh.BashCommands.ok;
import static brooklyn.util.ssh.BashCommands.sudo;
import static java.lang.String.format;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.lifecycle.ScriptHelper;
import brooklyn.entity.software.SshEffectorTasks;
import brooklyn.location.OsDetails;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Urls;
import brooklyn.util.os.Os;
import brooklyn.util.task.DynamicTasks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

// TODO: Alter -env ERL_CRASH_DUMP path in vm.args
public class RiakNodeSshDriver extends AbstractSoftwareProcessSshDriver implements RiakNodeDriver {

    private static final Logger LOG = LoggerFactory.getLogger(RiakNodeSshDriver.class);
    private static final String sbinPath = "$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private boolean isPackageInstall = false;
    private boolean isRiakOnPath = true;

    public RiakNodeSshDriver(final RiakNodeImpl entity, final SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public RiakNodeImpl getEntity() {
        return RiakNodeImpl.class.cast(super.getEntity());
    }

    @Override
    public Map<String, String> getShellEnvironment() {
        MutableMap<String, String> result = MutableMap.copyOf(super.getShellEnvironment());
        // how to change epmd port, according to 
        // http://serverfault.com/questions/582787/how-to-change-listening-interface-of-rabbitmqs-epmd-port-4369
        result.put("ERL_EPMD_PORT", "" + Integer.toString(getEntity().getEpmdListenerPort()));
        return result;
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this);
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("riak-%s", getVersion()))));
    }

    @Override
    public void install() {
        String saveAs = resolver.getFilename();

        OsDetails osDetails = getMachine().getMachineDetails().getOsDetails();
        List<String> commands = Lists.newLinkedList();
        if (osDetails.isLinux()) {
            commands.addAll(installLinux(getExpandedInstallDir()));
        } else if (osDetails.isMac()) {
            commands.addAll(installMac(saveAs));
        } else if (osDetails.isWindows()) {
            throw new UnsupportedOperationException("RiakNode not supported on Windows instances");
        } else {
            throw new IllegalStateException("Machine was not detected as linux, mac or windows! Installation does not know how to proceed with " +
                    getMachine() + ". Details: " + getMachine().getMachineDetails().getOsDetails());
        }
        newScript(INSTALLING)
                .body.append(commands)
                .execute();
    }

    private List<String> installLinux(String expandedInstallDir) {
        LOG.info("Ignoring version config ({}) and installing from package manager", getEntity().getConfig(RiakNode.SUGGESTED_VERSION));
        isPackageInstall = true;
        OsDetails osDetails = getMachine().getMachineDetails().getOsDetails();
        String osVersion = osDetails.getVersion();
        String osMajorVersion = osVersion.contains(".") ? osVersion.substring(0, osVersion.indexOf(".")) : osVersion;
        String fullVersion = getEntity().getConfig(RiakNode.SUGGESTED_VERSION);
        String majorVersion = fullVersion.substring(0, 3);
        String installBin = Urls.mergePaths(expandedInstallDir, "bin");
        String apt = chainGroup(
                //debian fix
                "export PATH=$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "which apt-get",
                ok(sudo("apt-get -y --allow-unauthenticated install apt-get install libpam0g-dev")),
                ok(sudo("apt-get -y --allow-unauthenticated install apt-get install libssl0.9.8")),
                // TODO: Debian support (default debian image fails with 'sudo: command not found')
                "[[ \"lucid natty precise\" =~ (^| )`lsb_release -sc`($| )  ]] && export OS_RELEASE=`lsb_release -sc` || export OS_RELEASE=precise",
                String.format("wget http://s3.amazonaws.com/downloads.basho.com/riak/%s/%s/ubuntu/$OS_RELEASE/riak_%<s-1_amd64.deb", majorVersion, fullVersion),
                sudo(String.format("dpkg -i riak_%s-1_amd64.deb", fullVersion)));
        String yum = chainGroup(
                "which yum",
                String.format("wget http://s3.amazonaws.com/downloads.basho.com/riak/%s/%s/rhel/%s/riak-%s-1.el6.x86_64.rpm", majorVersion, fullVersion, osMajorVersion, fullVersion),
                sudo(String.format("rpm -Uvh riak-%s-1.el6.x86_64.rpm", fullVersion)));
        return ImmutableList.<String>builder()
                .add("mkdir -p " + installBin)
                .add(INSTALL_CURL)
                .add(alternatives(apt, yum))
                .add("ln -s `which riak` " + Urls.mergePaths(installBin, "riak"))
                .add("ln -s `which riak-admin` " + Urls.mergePaths(installBin, "riak-admin"))
                .build();
    }

    private List<String> installMac(String saveAs) {
        String fullVersion = getEntity().getConfig(RiakNode.SUGGESTED_VERSION);
        String majorVersion = fullVersion.substring(0, 3);
        // Docs refer to 10.8. No download for 10.9 seems to exist.
        String hostOsVersion = "10.8";
        String url = String.format("http://s3.amazonaws.com/downloads.basho.com/riak/%s/%s/osx/%s/riak-%s-OSX-x86_64.tar.gz",
                majorVersion, fullVersion, hostOsVersion, fullVersion);
        return ImmutableList.<String>builder()
                .add(INSTALL_TAR)
                .add(INSTALL_CURL)
                .add(commandToDownloadUrlAs(url, saveAs))
                .add("tar xzvf " + saveAs)
                .build();
    }

    @Override
    public void customize() {
        //create entity's runDir
        newScript(CUSTOMIZING).execute();

        isRiakOnPath = isPackageInstall ? isRiakOnPath() : true;

        OsDetails osDetails = getMachine().getMachineDetails().getOsDetails();

        List<String> commands = Lists.newLinkedList();

        String vmArgsTemplate = processTemplate(entity.getConfig(RiakNode.RIAK_VM_ARGS_TEMPLATE_URL));
        String saveAsVmArgs = Urls.mergePaths(getRunDir(), "vm.args");
        DynamicTasks.queueIfPossible(SshEffectorTasks.put(saveAsVmArgs).contents(vmArgsTemplate));
        commands.add(sudo("mv " + saveAsVmArgs + " " + getRiakEtcDir()));

        String appConfigTemplate = processTemplate(entity.getConfig(RiakNode.RIAK_APP_CONFIG_TEMPLATE_URL));
        String saveAsAppConfig = Urls.mergePaths(getRunDir(), "app.config");
        DynamicTasks.queueIfPossible(SshEffectorTasks.put(saveAsAppConfig).contents(appConfigTemplate));
        commands.add(sudo("mv " + saveAsAppConfig + " " + getRiakEtcDir()));

        //increase open file limit (default min for riak is: 4096)
        //TODO: detect the actual limit then do the modificaiton.
        //TODO: modify ulimit for linux distros
        //    commands.add(sudo("launchctl limit maxfiles 4096 32768"));
        if (osDetails.isMac()) {
            commands.add("ulimit -n 4096");
        } else if (osDetails.isLinux()) {
            commands.add(sudo("chown riak:riak " + getVmArgsLocation()));
        }

        ScriptHelper customizeScript = newScript(CUSTOMIZING)
                .failOnNonZeroResultCode()
                .body.append(commands);

        if (!isRiakOnPath) {
            Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
            log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
            customizeScript.environmentVariablesReset(newPathVariable);
        }
        customizeScript.execute();

        //set the riak node name
        entity.setAttribute(RiakNode.RIAK_NODE_NAME, format("riak@%s", getHostname()));
    }

    @Override
    public void launch() {

        String command = format("%s start >/dev/null 2>&1 < /dev/null &", getRiakCmd());
        command = isPackageInstall ? "sudo " + command : command;

        ScriptHelper launchScript = newScript(LAUNCHING)
                .body.append(command);

        if (!isRiakOnPath) {
            Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
            log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
            launchScript.environmentVariablesReset(newPathVariable);
        }
        launchScript.execute();
    }

    @Override
    public void stop() {

        leaveCluster();

        String command = format("%s stop", getRiakCmd());
        command = isPackageInstall ? "sudo " + command : command;

        ScriptHelper stopScript = newScript(ImmutableMap.of(USE_PID_FILE, false), STOPPING)
                .body.append(command);

        if (!isRiakOnPath) {
            Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
            log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
            stopScript.environmentVariablesReset(newPathVariable);
        }

        int result = stopScript.execute();
        if (result != 0) {
            newScript(ImmutableMap.of(USE_PID_FILE, ""), STOPPING).execute();
        }
    }

    @Override
    public boolean isRunning() {

        // Version 2.0.0 requires sudo for `riak ping`
        ScriptHelper checkRunningScript = newScript(CHECK_RUNNING)
                .body.append(sudo(format("%s ping", getRiakCmd())));

        if (!isRiakOnPath) {
            Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
            log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
            checkRunningScript.environmentVariablesReset(newPathVariable);
        }
        return (checkRunningScript.execute() == 0);
    }

    public String getRiakEtcDir() {
        return isPackageInstall ? "/etc/riak" : Urls.mergePaths(getExpandedInstallDir(), "etc");
    }

    private String getRiakCmd() {
        return isPackageInstall ? "riak" : Urls.mergePaths(getExpandedInstallDir(), "bin/riak");
    }

    private String getRiakAdminCmd() {
        return isPackageInstall ? "riak-admin" : Urls.mergePaths(getExpandedInstallDir(), "bin/riak-admin");
    }

    @Override
    public void joinCluster(String nodeName) {
        //FIXME: find a way to batch commit the changes, instead of committing for every operation.

        if (getRiakName().equals(nodeName)) {
            log.warn("cannot join riak node: {} to itself", nodeName);
        } else {
            if (!hasJoinedCluster()) {

                ScriptHelper joinClusterScript = newScript("joinCluster")
                        .body.append(sudo(format("%s cluster join %s", getRiakAdminCmd(), nodeName)))
                        .failOnNonZeroResultCode();

                if (!isRiakOnPath) {
                    Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
                    log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
                    joinClusterScript.environmentVariablesReset(newPathVariable);
                }

                joinClusterScript.execute();

                entity.setAttribute(RiakNode.RIAK_NODE_HAS_JOINED_CLUSTER, Boolean.TRUE);
            } else {
                log.warn("entity {}: is already in the riak cluster", entity.getId());
            }
        }
    }

    @Override
    public void leaveCluster() {
        //TODO: add 'riak-admin cluster force-remove' for erroneous and unrecoverable nodes.
        //FIXME: find a way to batch commit the changes, instead of committing for every operation.
        //FIXME: find a way to check if the node is the last in the cluster to avoid removing the only member and getting "last node error"

        if (hasJoinedCluster()) {
            ScriptHelper leaveClusterScript = newScript("leaveCluster")
                    .body.append(sudo(format("%s cluster leave", getRiakAdminCmd())))
                    .body.append(sudo(format("%s cluster plan", getRiakAdminCmd())))
                    .body.append(sudo(format("%s cluster commit", getRiakAdminCmd())));

            if (!isRiakOnPath) {
                Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
                log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
                leaveClusterScript.environmentVariablesReset(newPathVariable);
            }

            leaveClusterScript.execute();

            entity.setAttribute(RiakNode.RIAK_NODE_HAS_JOINED_CLUSTER, Boolean.FALSE);
        } else {
            log.warn("entity {}: is not in the riak cluster", entity.getId());
        }
    }

    @Override
    public void commitCluster() {

        if (hasJoinedCluster()) {
            ScriptHelper commitClusterScript = newScript("commitCluster")
                    .body.append(format("%s cluster plan", getRiakAdminCmd()))
                    .body.append(format("%s cluster commit", getRiakAdminCmd()));

            if (!isRiakOnPath) {
                Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
                log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
                commitClusterScript.environmentVariablesReset(newPathVariable);
            }
            commitClusterScript.execute();

        } else {
            log.warn("entity {}: is not in the riak cluster", entity.getId());
        }
    }

    @Override
    public void recoverFailedNode(String nodeName) {

        //TODO find ways to detect a faulty/failed node
        //argument passed 'node' is any working node in the riak cluster
        //following the instruction from: http://docs.basho.com/riak/latest/ops/running/recovery/failed-node/

        if (hasJoinedCluster()) {
            String failedNodeName = getRiakName();


            String stopCommand = format("%s stop", getRiakCmd());
            stopCommand = isPackageInstall ? "sudo " + stopCommand : stopCommand;

            String startCommand = format("%s start >/dev/null 2>&1 < /dev/null &", getRiakCmd());
            startCommand = isPackageInstall ? "sudo " + startCommand : startCommand;

            ScriptHelper recoverNodeScript = newScript("recoverNode")
                    .body.append(stopCommand)
                    .body.append(format("%s down %s", getRiakAdminCmd(), failedNodeName))
                    .body.append(sudo(format("rm -rf %s", getRingStateDir())))
                    .body.append(startCommand)
                    .body.append(sudo(format("%s cluster join %s", getRiakAdminCmd(), nodeName)))
                    .body.append(sudo(format("%s cluster plan", getRiakAdminCmd())))
                    .body.append(sudo(format("%s cluster commit", getRiakAdminCmd())))
                    .failOnNonZeroResultCode();

            if (!isRiakOnPath) {
                Map<String, String> newPathVariable = ImmutableMap.of("PATH", sbinPath);
                log.warn("riak command not found on PATH. Altering future commands' environment variables from {} to {}", getShellEnvironment(), newPathVariable);
                recoverNodeScript.environmentVariablesReset(newPathVariable);
            }

            recoverNodeScript.execute();

        } else {
            log.warn("entity {}: is not in the riak cluster", entity.getId());
        }
    }
    
    @Override
    public void riakAdmin(String command) {
        newScript("riakAdmin")
            .body.append(sudo(getRiakAdminCmd() + " " + command))
            .gatherOutput()
            .execute();
    }

    private String getVmArgsLocation() {
        return Urls.mergePaths(getRiakEtcDir(), "vm.args");
    }

    private Boolean hasJoinedCluster() {
        return ((RiakNode) entity).hasJoinedCluster();
    }

    private boolean isRiakOnPath() {
        return (newScript("riakOnPath")
                .body.append("which riak")
                .execute() == 0);
    }

    private String getRiakName() {
        return entity.getAttribute(RiakNode.RIAK_NODE_NAME);
    }

    private String getRingStateDir() {
        //TODO: check for non-package install.
        return isPackageInstall ? "/var/lib/riak/ring" : Urls.mergePaths(getExpandedInstallDir(), "lib/ring");
    }

    @Override
    public void deleteAllData() {
        newScript("deleteAllData")
            .body.append("rm -rf data")
            .failOnNonZeroResultCode()
            .execute();
    }
}
