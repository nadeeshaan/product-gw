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

import org.apache.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Camel configuration file watching agent implementation
 */
public class CamelConfigWatchAgent {
    private static final Logger log = Logger.getLogger(CamelConfigWatchAgent.class);

    private ExecutorService pool = Executors.newFixedThreadPool(10);

    public void startWatchingForModifications(Path path, GatewayNettyInitializer gatewayNettyInitializer)
            throws Exception {
        log.info("Watching path : " + path.toString());
        try {
            Future<Integer> future = pool.submit(() -> {
                try {
                    Boolean isFolder = (Boolean) Files.getAttribute(path,
                                                                    "basic:isDirectory", NOFOLLOW_LINKS);
                    if (!isFolder) {
                        throw new IllegalArgumentException("Path: " + path + " is not a folder");
                    }
                } catch (IOException ioe) {
                    throw ioe;
                }

                FileSystem fs = path.getFileSystem();
                try (WatchService service = fs.newWatchService()) {
                    path.register(service, ENTRY_MODIFY, ENTRY_CREATE);
                    WatchKey key;
                    while (true) {
                        log.info("Watch service run...... ");
                        key = service.take();
                        WatchEvent.Kind<?> kind;

                        log.info("FileWatcher event detected!");
                        for (WatchEvent<?> watchEvent : key.pollEvents()) {
                            kind = watchEvent.kind();
                            if (OVERFLOW == kind) {
                                continue; //loop
                            } else if (ENTRY_MODIFY == kind) {
                                log.info("File modification detected.");
                                @SuppressWarnings("unchecked")
                                Path newPath = ((WatchEvent<Path>) watchEvent).context();
                                if (!newPath.toString().startsWith(".")
                                    &&  !newPath.toString().startsWith(".swp")) {
                                    File modifiedFile = new File(path.toString() + File.separator + newPath.toString());
                                    gatewayNettyInitializer.notifyRoutesModification(modifiedFile);
                                }
                            } else if (ENTRY_CREATE == kind) {
                                log.info("New File creation Detected");

                                /**
                                 * TODO: If we handle the entry create event here, same config will be added twice
                                 * This is because when creating a file, OS will create an empty file with 0bytes and
                                 * then it will modify the file with the content
                                 * Then the ENTRY_MODIFY event is triggered Need to check this behavior on other OSs
                                 */
                            }
                        }
                        key.reset();
                    }

                } catch (Exception e) {
                    throw e;
                }
            });
            if (future == null) {
                log.error("Camel config watcher has failed.!");
            }
        } catch (Exception e) {
            throw e;
        }
    }
}
