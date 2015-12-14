/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.gateway.internal.transport.listener;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.apache.camel.model.RoutesDefinition;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.w3c.dom.Document;
import org.wso2.carbon.gateway.internal.common.CarbonMessage;
import org.wso2.carbon.gateway.internal.mediation.camel.CamelMediationComponent;
import org.wso2.carbon.gateway.internal.mediation.camel.CamelMediationEngine;
import org.wso2.carbon.gateway.internal.mediation.camel.CarbonMessageReverseTypeConverter;
import org.wso2.carbon.gateway.internal.mediation.camel.CarbonMessageTypeConverter;
import org.wso2.carbon.gateway.internal.transport.common.Constants;
import org.wso2.carbon.gateway.internal.transport.common.disruptor.config.DisruptorConfig;
import org.wso2.carbon.gateway.internal.transport.common.disruptor.config.DisruptorFactory;
import org.wso2.carbon.gateway.internal.transport.sender.channel.BootstrapConfiguration;
import org.wso2.carbon.gateway.internal.transport.sender.channel.pool.ConnectionManager;
import org.wso2.carbon.gateway.internal.transport.sender.channel.pool.PoolConfiguration;
import org.wso2.carbon.transport.http.netty.listener.CarbonNettyServerInitializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Map;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamSource;


/**
 * A class that responsible for create server side channels.
 */
public class GatewayNettyInitializer implements CarbonNettyServerInitializer {

    private static final Logger log = Logger.getLogger(GatewayNettyInitializer.class);
    private int queueSize = 32544;
    private ConnectionManager connectionManager;
    private ApplicationContext applicationContext;
    private SpringCamelContext camelContext;

    public static final String CAMEL_CONTEXT_CONFIG_FILE = "repository" + File.separator + "conf" +
            File.separator +
            "camel" + File.separator
            + "camel-context.xml";

    public static final  String CAMEL_CONFIGS_DIRECTORY = "repository" + File.separator + "conf" +
            File.separator + "camel";

    public GatewayNettyInitializer() {

    }

    @Override
    public void setup(Map<String, String> parameters) {
        BootstrapConfiguration.createBootStrapConfiguration(parameters);
        PoolConfiguration.createPoolConfiguration(parameters);

        SpringCamelContext.setNoStart(true);
        applicationContext = new ClassPathXmlApplicationContext(
                new String[]{CAMEL_CONTEXT_CONFIG_FILE});
        try {
            camelContext = (SpringCamelContext) applicationContext.getBean("wso2-cc");
            camelContext.start();
            CamelMediationComponent component = (CamelMediationComponent) camelContext.getComponent("wso2-gw");
            camelContext.getTypeConverterRegistry().addTypeConverter(Document.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(InputStream.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(DOMSource.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(SAXSource.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(StAXSource.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(StreamSource.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(String.class, CarbonMessage.class,
                    new CarbonMessageTypeConverter());
            camelContext.getTypeConverterRegistry().addTypeConverter(CarbonMessage.class, String.class,
                    new CarbonMessageReverseTypeConverter());
            CamelMediationEngine engine = component.getEngine();
            connectionManager = component.getConnectionManager();

            if (parameters != null) {
                DisruptorConfig disruptorConfig =
                        new DisruptorConfig(
                                parameters.get(Constants.DISRUPTOR_BUFFER_SIZE),
                                parameters.get(Constants.DISRUPTOR_COUNT),
                                parameters.get(Constants.DISRUPTOR_EVENT_HANDLER_COUNT),
                                parameters.get(Constants.WAIT_STRATEGY),
                                Boolean.parseBoolean(Constants.SHARE_DISRUPTOR_WITH_OUTBOUND));
                DisruptorFactory.createDisruptors(DisruptorFactory.DisruptorType.INBOUND,
                        disruptorConfig, engine);
                String queueSize = parameters.get(Constants.CONTENT_QUEUE_SIZE);
                if (queueSize != null) {
                    this.queueSize = Integer.parseInt(queueSize);
                }
            } else {
                log.warn("Disruptor specific parameters are not specified in " +
                        "configuration hence using default configs");
                DisruptorConfig disruptorConfig = new DisruptorConfig();
                DisruptorFactory.createDisruptors(DisruptorFactory.DisruptorType.INBOUND,
                        disruptorConfig, engine);
            }

            // Add the routes from the custom routes config files in the repository/conf/camel directory
            log.info("Adding routes from custom routes configuration files ...");
            addRoutesToContext(CAMEL_CONFIGS_DIRECTORY);

            // Start the watch service for the custom route file modification in the repository/conf/camel directory
            new CamelConfigWatchAgent().startWatchingForModifications(Paths.get("repository" + File.separator
                    + "conf" +
                    File.separator +
                    "camel"), this);
        } catch (Exception e) {
            String msg = "Error while loading " + CAMEL_CONTEXT_CONFIG_FILE + " configuration file";
            log.error(msg + e);
            throw new RuntimeException(msg, e);
        }
    }

    @Override
    public void initChannel(SocketChannel ch) {
        if (log.isDebugEnabled()) {
            log.info("Initializing source channel pipeline");
        }
        ChannelPipeline p = ch.pipeline();
        p.addLast("decoder", new HttpRequestDecoder());
        p.addLast("encoder", new HttpResponseEncoder());
        try {
            p.addLast("handler", new SourceHandler(queueSize, connectionManager));
        } catch (Exception e) {
            log.error("Cannot Create SourceHandler ", e);
        }
    }

    /**
     * Adding the routes from custom configuration files
     * @param routeConfig custom configuration file
     */
    public void addRoutesFromCustomConfigs(File routeConfig) throws FileNotFoundException {
        log.info("Adding Custom Routes from [" + routeConfig.getName() + "]");

        if (routeConfig.getPath().equals(CAMEL_CONTEXT_CONFIG_FILE)) {
            log.warn("Skipping camel Context file [" + routeConfig.getName() + "]");
            return;
        }

        InputStream inputStream = new FileInputStream(routeConfig.getAbsolutePath());
        try {
            RoutesDefinition routesDefinition = camelContext.loadRoutesDefinition(inputStream);
            camelContext.addRouteDefinitions(routesDefinition.getRoutes());
        } catch (Exception e) {
            String msg = "Error while adding the routes from file [" + routeConfig.getName() + "] ";
            log.error(msg + e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                String msg = "Error while Closing the input stream";
                log.error(msg + e);
            }
        }
    }

    /**
     * Adding the routes from the files listed under the directory given by the path
     * @param path location of the particular directory
     */
    private void addRoutesToContext(String path) throws FileNotFoundException {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            log.warn("Specified path is not a Directory");
            return;
        }

        File[] files = directory.listFiles();

        // Iterate over the files in the specified directory and load routes from them
        if (files != null) {
            for (File file : files) {
                addRoutesFromCustomConfigs(file);
            }
        }
    }


}
