/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.spdy.glassfish;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.util.SystemPropertyConstants;
import java.util.Locale;
import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.grizzly.config.dom.Http;
import org.glassfish.grizzly.config.dom.NetworkConfig;
import org.glassfish.grizzly.config.dom.Protocol;
import org.glassfish.grizzly.config.dom.Protocols;
import org.glassfish.grizzly.config.dom.Spdy;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Target;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;

import javax.inject.Inject;
import javax.inject.Named;

@SuppressWarnings("UnusedDeclaration")
@Service(name = "enable-spdy")
@PerLookup
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
@TargetType({CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CONFIG})
public class EnableSpdyCommand implements AdminCommand {

    @Param(name = "protocolname", primary = true, optional = false)
    String protocolName;

    @Param(name = "target", optional = true, defaultValue = SystemPropertyConstants.DAS_SERVER_NAME)
    String target;

    @Param(name = "max-concurrent-streams", optional = true, defaultValue = "100")
    String maxStreams;

    @Param(name = "initial-window-size-bytes", optional = true, defaultValue = "65536")
    String initialWindowSize;

    @Param(name = "max-frame-length-in-bytes", optional = true, defaultValue = "16777216")
    String maxFrameLengthInBytes;

    @Param(name = "mode", optional = true, defaultValue = "npn")
    String mode;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    Config config;

    @Inject
    ServiceLocator services;

    /**
     * Executes the command with the command parameters passed as Properties where the keys are the paramter names and
     * the values the parameter values
     *
     * @param context information
     */
    public void execute(AdminCommandContext context) {
        Target targetUtil = services.getService(Target.class);
        Config newConfig = targetUtil.getConfig(target);
        if (newConfig != null) {
            config = newConfig;
        }
        final ActionReport report = context.getActionReport();
        NetworkConfig networkConfig = config.getNetworkConfig();
        Protocols protocols = networkConfig.getProtocols();
        Protocol protocol = null;
        for (Protocol p : protocols.getProtocol()) {
            if (protocolName.equalsIgnoreCase(p.getName())) {
                protocol = p;
            }
        }
        if (protocol == null) {
            report.setMessage(String.format("Unable to find protocol %s.", protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        final Http http = protocol.getHttp();
        if (http == null) {
            report.setMessage(String.format("Protocol, %s, is not an HTTP enabled protocol.", protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        try {
            ConfigSupport.apply(new SingleConfigCode<Http>() {
                public Object run(Http param) throws TransactionFailure {
                    Spdy spdy = param.createChild(Spdy.class);
                    if (maxStreams != null) {
                        int maxStreamsLocal = Integer.parseInt(maxStreams);
                        if (maxStreamsLocal > 0) {
                            spdy.setMaxConcurrentStreams(maxStreamsLocal);
                        }
                    }
                    if (initialWindowSize != null) {
                        int initialWindowSizeLocal = Integer.parseInt(initialWindowSize);
                        if (initialWindowSizeLocal > 0) {
                            spdy.setInitialWindowSizeInBytes(initialWindowSizeLocal);
                        }
                    }
                    if (maxFrameLengthInBytes != null) {
                        int maxFrameLengthInBytesLocal = Integer.parseInt(maxFrameLengthInBytes);
                        if (maxFrameLengthInBytesLocal > 0 && maxFrameLengthInBytesLocal < (1 << 24)) {
                            spdy.setMaxFrameLengthInBytes(maxFrameLengthInBytesLocal);
                        }
                    }
                    if (mode != null) {
                        String modeLocal = mode.toLowerCase(Locale.US);
                        spdy.setMode(modeLocal);
                    }
                    param.setSpdy(spdy);
                return spdy;
                }
            }, http);
        } catch (TransactionFailure transactionFailure) {
            report.setMessage(String.format("Unable to enable SPDY for protocol %s.  See log for details.", protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(transactionFailure);
            return;
        } catch (Exception e) {
            report.setMessage(String.format("Unable to enable SPDY for protocol %s.  See log for details.", protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;
        }
        report.appendMessage("SPDY enabled.\n ** NOTE** This is an experimental feature!\nFor any issues with the SPDY implementation, please log issues here: http://java.net/jira/browse/GRIZZLY .");
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);

    }

}