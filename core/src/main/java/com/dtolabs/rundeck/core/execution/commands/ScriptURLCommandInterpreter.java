/*
 * Copyright 2012 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
* ScriptURLCommandInterpreter.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 5/2/12 2:37 PM
* 
*/
package com.dtolabs.rundeck.core.execution.commands;

import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.common.UpdateUtils;
import com.dtolabs.rundeck.core.common.impl.URLFileUpdater;
import com.dtolabs.rundeck.core.common.impl.URLFileUpdaterBuilder;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.ExecutionException;
import com.dtolabs.rundeck.core.execution.ExecutionItem;
import com.dtolabs.rundeck.core.execution.ExecutionService;
import com.dtolabs.rundeck.core.execution.service.FileCopierException;
import com.dtolabs.rundeck.core.execution.service.NodeExecutorResult;
import com.dtolabs.rundeck.core.utils.Converter;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.log4j.Logger;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * ScriptURLCommandInterpreter is a CommandInterpreter for executing a script retrieved from a URL.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class ScriptURLCommandInterpreter implements CommandInterpreter {
    public static final Logger logger = Logger.getLogger(ScriptURLCommandInterpreter.class.getName());
    public static final String SERVICE_IMPLEMENTATION_NAME = "script-url";

    public static final int DEFAULT_TIMEOUT = 30;
    public static final boolean USE_CACHE = true;

    private File cacheDir;

    private Framework framework;
    URLFileUpdater.httpClientInteraction interaction;

    public ScriptURLCommandInterpreter(Framework framework) {
        this.framework = framework;
        cacheDir = new File(Constants.getBaseVar(framework.getBaseDir().getAbsolutePath())
                            + "/cache/ScriptURLCommandInterpreter");
    }

    private static String hashURL(final String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.reset();
            digest.update(url.getBytes(Charset.forName("UTF-8")));
            return new String(Hex.encodeHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return Integer.toString(url.hashCode());
    }

    public InterpreterResult interpretCommand(ExecutionContext context, ExecutionItem item, INodeEntry node) throws
        InterpreterException {
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new RuntimeException("Unable to create cachedir: " + cacheDir.getAbsolutePath());
        }
        final ScriptURLCommandExecutionItem script = (ScriptURLCommandExecutionItem) item;
        final ExecutionService executionService = framework.getExecutionService();
        //create node context for node and substitute data references in command
        final Map<String, Map<String, String>> nodeDataContext =
            DataContextUtils.addContext("node", DataContextUtils.nodeData(node), context.getDataContext());

        final String finalUrl = expandUrlString(script.getURLString(), nodeDataContext);
        final URL url;
        try {
            url = new URL(finalUrl);
        } catch (MalformedURLException e) {
            throw new InterpreterException(e);
        }
        if(null!=context.getExecutionListener()){
            context.getExecutionListener().log(4, "Requesting URL: " + url.toExternalForm());
        }

        String tempFileName = hashURL(url.toExternalForm()) + ".temp";
        File destinationTempFile = new File(cacheDir, tempFileName);
        File destinationCacheData = new File(cacheDir, tempFileName + ".cache.properties");

        //update from URL if necessary
        final URLFileUpdaterBuilder urlFileUpdaterBuilder = new URLFileUpdaterBuilder()
            .setUrl(url)
            .setAcceptHeader("*/*")
            .setTimeout(DEFAULT_TIMEOUT);
        if (USE_CACHE) {
            urlFileUpdaterBuilder
                .setCacheMetadataFile(destinationCacheData)
                .setCachedContent(destinationTempFile)
                .setUseCaching(true);
        }
        final URLFileUpdater updater = urlFileUpdaterBuilder.createURLFileUpdater();
        try {
            if (null != interaction) {
            //allow mock
                updater.setInteraction(interaction);
            }
            UpdateUtils.update(updater, destinationTempFile);

            logger.debug("Updated nodes resources file: " + destinationTempFile);
        } catch (UpdateUtils.UpdateException e) {
            if (!destinationTempFile.isFile() || destinationTempFile.length() < 1) {
                throw new InterpreterException(
                    "Error requesting URL Script: " + url + ": " + e.getMessage(), e);
            } else {
                logger.error(
                    "Error requesting URL script: " + url + ": " + e.getMessage(), e);
            }
        }

        final String filepath; //result file path
        try {
            filepath = executionService.fileCopyFile(context, destinationTempFile, node);
        } catch (FileCopierException e) {
            throw new InterpreterException(e);
        }

        try {
            /**
             * TODO: Avoid this horrific hack. Discover how to get SCP task to preserve the execute bit.
             */
            if (!"windows".equalsIgnoreCase(node.getOsFamily())) {
                //perform chmod+x for the file

                final NodeExecutorResult nodeExecutorResult = framework.getExecutionService().executeCommand(
                    context, new String[]{"chmod", "+x", filepath}, node);
                if (!nodeExecutorResult.isSuccess()) {
                    return nodeExecutorResult;
                }
            }

            final String[] args = script.getArgs();
            //replace data references
            String[] newargs = null;
            if (null != args && args.length > 0) {
                newargs = new String[args.length + 1];
                final String[] replargs = DataContextUtils.replaceDataReferences(args, nodeDataContext);
                newargs[0] = filepath;
                System.arraycopy(replargs, 0, newargs, 1, replargs.length);
            } else {
                newargs = new String[]{filepath};
            }

            return framework.getExecutionService().executeCommand(context, newargs, node);
        } catch (ExecutionException e) {
            throw new InterpreterException(e);
        }
    }

    public static final Converter<String, String> urlPathEncoder = new Converter<String, String>() {
        public String convert(String s) {
            try {
                return URIUtil.encodeWithinPath(s, "UTF-8");
            } catch (URIException e) {
                e.printStackTrace();
                return s;
            }
        }
    };
    public static final Converter<String, String> urlQueryEncoder = new Converter<String, String>() {
        public String convert(String s) {
            try {
                return URIUtil.encodeWithinQuery(s, "UTF-8");
            } catch (URIException e) {
                e.printStackTrace();
                return s;
            }
        }
    };

    /**
     * Expand data references in a URL string, using proper encoding for path and query parts.
     */
    public static String expandUrlString(final String urlString, final Map<String, Map<String, String>> dataContext) {
        final String origUrl = urlString;
        final int qindex = origUrl.indexOf("?");
        final StringBuilder builder = new StringBuilder();
        if (qindex > 0) {
            builder.append(DataContextUtils.replaceDataReferences(origUrl.substring(0, qindex),
                dataContext, urlPathEncoder, true));
            builder.append("?");
            if (qindex < origUrl.length() - 1) {
                builder.append(DataContextUtils.replaceDataReferences(origUrl.substring(qindex + 1),
                    dataContext, urlQueryEncoder, true));
            }
            return builder.toString();
        } else {
            return DataContextUtils.replaceDataReferences(urlString, dataContext,
                urlPathEncoder, false);
        }
    }

    URLFileUpdater.httpClientInteraction getInteraction() {
        return interaction;
    }

    void setInteraction(URLFileUpdater.httpClientInteraction interaction) {
        this.interaction = interaction;
    }
}
