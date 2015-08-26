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
package org.apache.brooklyn.cli;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;

public class BrooklynDaemon implements Daemon {

    private Thread mainThread;

    @Override
    public void init(DaemonContext daemonContext) {
        final String[] args = daemonContext.getArguments();

        mainThread = new Thread(){
            @Override
            public synchronized void start() {
                super.start();
            }

            @Override
            public void run() {
                Main.main(args);
            }
        };
    }

    @Override
    public void start() throws Exception {
        mainThread.start();
    }

    @Override
    public void stop() throws Exception {
        mainThread.join(10000);
    }

    @Override
    public void destroy() {
        mainThread = null;
    }
}
