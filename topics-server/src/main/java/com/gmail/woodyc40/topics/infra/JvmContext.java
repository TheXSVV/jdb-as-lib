/*
 * JDB - Java Debugger
 * Copyright 2017 Johnny Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gmail.woodyc40.topics.infra;

import com.gmail.woodyc40.topics.server.AgentServer;
import com.google.common.collect.Maps;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.tools.jdi.ProcessAttachingConnector;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds the current state of the JVM which is being
 * debugged, i.e. breakpoints and source paths.
 */
@NotThreadSafe
@RequiredArgsConstructor
public final class JvmContext {

    private static final Logger LOGGER = LogManager.getLogger(JvmContext.class);

    /** Instance of the context */
    private static volatile JvmContext instance;
    /** The collection of paths leading to class sources */
    @Getter
    private final Map<String, Path> sourcePath = Maps.newHashMap();
    /** Mapping of FQN:LN breakpoint info to disable breakpoints */
    @Getter
    private final Map<String, BreakpointRequest> breakpoints = new HashMap<>();
    /** The previous breakpoint frames */
    @Getter
    private final Queue<List<StackFrame>> previousFrames = new ConcurrentLinkedQueue<>();
    @Getter
    private final Queue<Frame> returns = new ConcurrentLinkedQueue<>();
    /** Lock used to protect the breakpoint events */
    @Getter
    private final Object lock = new Object();
    /** The agent server used to receive method bytes */
    @Getter
    private final AgentServer server;
    /** Should the VM exit() when detached? */
    @Getter
    @Setter
    public volatile boolean closeOnDetach = true;
    /** Currently attached JVM PID */
    @Getter
    private volatile int currentPid = -1;
    /** The virtual machine that is currently attached */
    @Getter
    private VirtualMachine vm;
    /** The listener thread for VM breakpoints */
    private volatile Thread breakpointListener;
    /** The class that is currently being modified */
    @Getter
    @Setter
    private ReferenceType currentRef;
    /** The current breakpoint that is active on the VM */
    @GuardedBy("lock")
    @Getter
    @Setter
    private BreakpointEvent currentBreakpoint;
    /** The current eventSet used by the current breakpoint */
    @GuardedBy("lock")
    @Getter
    @Setter
    private EventSet resumeSet;

    /**
     * Initializes the context singleton.
     *
     * @param server the server to initialize
     */
    public static void init(AgentServer server) {
        instance = new JvmContext(server);
    }

    /**
     * Obtains the singleton instance of this context.
     *
     * @return the JVM context wrapper singleton
     */
    public static JvmContext getContext() {
        return instance;
    }

    /**
     * Sets the current JVM context to that of a JVM running
     * at the given PID number.
     *
     * @param pid the process ID to attach
     */
    public void attach(int pid) {
        if (this.currentPid == pid) {
            LOGGER.warn("Process already attached!");
            return;
        }

        if (this.currentPid > 0) {
            LOGGER.info("Currently attached to {}", this.currentPid);
        }

        String procData;
        try {
            procData = getAvailablePids().get(pid);
        } catch (IOException | InterruptedException exception) {
            exception.printStackTrace();
            return;
        }

        if (procData == null) {
            LOGGER.error("No JVM found with PID {}", pid);
            return;
        }

        LOGGER.info("Attaching to {}...", pid);
        this.currentPid = pid;

        List<AttachingConnector> connectors = Bootstrap.virtualMachineManager().attachingConnectors();
        ProcessAttachingConnector pac = null;
        for (AttachingConnector connector : connectors)
            if (connector.name().equals("com.sun.jdi.ProcessAttach"))
                pac = (ProcessAttachingConnector) connector;

        if (pac == null) {
            LOGGER.error("ProcessAttach not found");
            return;
        }

        Map<String, Connector.Argument> args = pac.defaultArguments();
        Connector.Argument arg = args.get("pid");
        if (arg == null) {
            LOGGER.error("Corrupt transport (pid not found in arguments)");
            return;
        }

        arg.setValue(String.valueOf(pid));
        try {
            this.vm = pac.attach(args);
            this.breakpointListener = new Thread(new Runnable() {
                final EventQueue queue = vm.eventQueue();

                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            EventSet eventSet = this.queue.remove();

                            for (Event event : eventSet) {
                                if (event instanceof BreakpointEvent) {
                                    BreakpointEvent e = (BreakpointEvent) event;

                                    List<StackFrame> frames = new ArrayList<>();
                                    for (int i = 0; i < Integer.MAX_VALUE; i++) {
                                        try {
                                            frames.add(e.thread().frame(i));
                                        } catch (IncompatibleThreadStateException e1) {
                                            e1.printStackTrace();
                                        } catch (IndexOutOfBoundsException e1) {
                                            break;
                                        }
                                    }
                                    previousFrames.add(frames);

                                    synchronized (lock) {
                                        currentBreakpoint = e;
                                        resumeSet = eventSet;
                                    }
                                    LOGGER.info("Hit breakpoint {}: {}", e.location().sourceName(), e.location().lineNumber());

                                    String string = lookupLine(e.location().declaringType().name(), e.location().lineNumber(), 3);
                                    if (string != null && !string.isEmpty()) {
                                        LOGGER.info("Code context:");
                                        LOGGER.info(string);
                                    }
                                } else if (event instanceof MethodExitEvent) {
                                    MethodExitEvent exit = (MethodExitEvent) event;
                                    Value value = exit.returnValue();
                                    ThreadReference thread = exit.thread();
                                    if (!thread.isAtBreakpoint()) {
                                        eventSet.resume();
                                        continue;
                                    }

                                    StackFrame frame;
                                    try {
                                        frame = thread.frames().get(0);
                                    } catch (IncompatibleThreadStateException e) {
                                        LOGGER.error("Wrong thread state");
                                        continue;
                                    }

                                    returns.add(new Frame(exit.location(), value, frame.location().method().toString(), System.currentTimeMillis()));
                                }
                            }
                        } catch (AbsentInformationException e) {
                            throw new RuntimeException(e);
                        } catch (VMDisconnectedException e) {
                            breakpointListener = null;
                            JvmContext.this.detach();
                            break;
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            });
            this.breakpointListener.setDaemon(true);
            this.breakpointListener.start();
        } catch (IOException | IllegalConnectorArgumentsException exception) {
            this.currentPid = -1;
            exception.printStackTrace();
            return;
        }

        LOGGER.info("Successfully attached to {}", pid);
    }

    /**
     * Detaches from the currently attached JVM, or fails.
     */
    public void detach() {
        if (this.currentPid == -1) {
            return;
        }

        LOGGER.info("Detached from JVM {}", this.currentPid);
        this.currentPid = -1;

        if (this.breakpointListener != null) {
            this.breakpointListener.interrupt();

            for (BreakpointRequest request : this.breakpoints.values()) {
                request.disable();
            }
            this.breakpointListener = null;

            if (this.closeOnDetach) {
                this.vm.exit(3);
                return;
            }

            this.vm.dispose();
        }

        this.server.close();
        this.currentRef = null;
        this.sourcePath.clear();
        this.previousFrames.clear();
        synchronized (this.lock) {
            this.currentBreakpoint = null;
            this.resumeSet = null;
        }
        this.breakpoints.clear();
        this.vm = null;
    }

    /**
     * Obtains the available JVM PIDs running on the current
     * system.
     *
     * @return a mapping of PIDs to process path
     * @throws IOException if the system cannot be polled
     * @throws InterruptedException if the process did not
     * exit
     */
    public Map<Integer, String> getAvailablePids() throws IOException, InterruptedException {
        Map<Integer, String> availablePids = Maps.newHashMap();
        String[] cmd = Platform.isWindows() ? new String[] { "wmic", "process", "where", "\"name='java.exe'\"", "get", "commandline,processid" } :
                new String[] { "/bin/sh", "-c", "ps -e --format pid,args | grep java" };
        Process ls = new ProcessBuilder().
                command(cmd).
                start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ls.getInputStream()))) {
            if (Platform.isWindows()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("CommandLine")) {
                        continue;
                    }

                    line = line.trim();
                    int point = line.lastIndexOf("  ");
                    String pid = line.substring(point + 2);
                    String procName = line.substring(0, 200).trim();
                    availablePids.put(Integer.parseInt(pid), procName + (procName.length() < 200 ? "" : "..."));
                }
            } else {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.contains("grep java")) {
                        continue;
                    }

                    line = line.trim();
                    int firstSeparator = line.indexOf(' ');
                    String pid = line.substring(0, firstSeparator);
                    String procName = line.substring(firstSeparator + 1, firstSeparator + 100).trim();
                    availablePids.put(Integer.parseInt(pid), procName + (procName.length() < 100 ? "" : "...  "));
                }
            }
        }

        ls.waitFor();
        ls.destroy();
        return availablePids;
    }

    /**
     * Looksup the source line with the given location
     * information and amount of context.
     *
     * @param cls the class to lookup
     * @param ln the line number to use
     * @param context the number of lines before and after
     * to include
     * @return the source line
     */
    public String lookupLine(String cls, int ln, int context) {
        Path path = this.sourcePath.get(cls);
        if (path == null) {
            return null;
        } else {
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                for (int i = 1; ; i++) {
                    String line = reader.readLine();
                    if (i >= ln - context && i <= ln + context) {
                        if (i == ln + context) {
                            builder.append(line);
                            break;
                        }

                        if (i == ln) {
                            builder.append('>');
                        }

                        builder.append(line).append('\n');
                    }
                }

                return builder.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
