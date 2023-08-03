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
package com.gmail.woodyc40.topics;

import com.gmail.woodyc40.topics.consumer.MultipleMatchConsumer;
import com.gmail.woodyc40.topics.exceptions.NoBreakpointFoundException;
import com.gmail.woodyc40.topics.exceptions.NoReferenceFoundException;
import com.gmail.woodyc40.topics.infra.Frame;
import com.gmail.woodyc40.topics.infra.JvmContext;
import com.gmail.woodyc40.topics.protocol.SignalOutReqMethod;
import com.gmail.woodyc40.topics.server.AgentServer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sun.jdi.*;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Queue;

@Getter
public class JDB {

    private static final Logger LOGGER = LogManager.getLogger(JDB.class);
    /* Default port for agent */
    private static final int DEFAULT_PORT = 5000;

    /* Port of the agent */
    private final int port;
    /* Instance of agent server */
    private final AgentServer server;

    public JDB(boolean setupShutdownHook) {
        this(setupShutdownHook, DEFAULT_PORT);
    }

    public JDB(boolean setupShutdownHook, int port) {
        this.port = port;
        this.server = AgentServer.initServer(port);

        if (setupShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                JvmContext.getContext().detach();
            }, "Detach hook"));
        }
    }

    /**
     * Initializates the JDB
     * @return JDB instance
     */
    public JDB start() {
        JvmContext.init(server);
        return this;
    }

    /**
     * Returns the Map with process line and pid
     *
     * @return ImmutableMap<Integer, String>
     */
    @SneakyThrows
    public ImmutableMap<Integer, String> getAviablePids() {
        return ImmutableMap.copyOf(JvmContext.getContext().getAvailablePids());
    }

    /**
     * Attaches to the active running JVM by PID
     *
     * @param pid process id of the active running JVM
     * @return JDB instance
     */
    public JDB attach(int pid) {
        JvmContext.getContext().attach(pid);
        return this;
    }

    /**
     * Obtains a class reference type from the given name.
     *
     * @param name the name of the reference to find
     * @param matchConsumer the consumer if references more than 0
     * @return the reference type as represented in the JDI
     */
    public ReferenceType getReference(String name, MultipleMatchConsumer matchConsumer) {
        VirtualMachine vm = JvmContext.getContext().getVm();
        List<ReferenceType> matches = Lists.newLinkedList();
        for (ReferenceType type : vm.allClasses())
            if (type.name().toLowerCase().contains(name.toLowerCase()))
                matches.add(type);

        if (matches.isEmpty())
            LOGGER.error("No class found for {}", name);
        else if (matches.size() == 1)
            return matches.get(0);
        else {
            int line = matchConsumer.process(matches);

            if (line >= matches.size() || line < 0)
                LOGGER.error("Index out of bounds");
            else
                return matches.get(line);
        }

        return null;
    }

    /**
     * Returns the Map from breakpoint method returns
     *
     * @return ImmutableMap<Method, Value>
     */
    public ImmutableMap<Method, Value> returns() throws NoBreakpointFoundException {
        Map<Method, Value> returnMap = Maps.newLinkedHashMap();
        JvmContext context = JvmContext.getContext();
        BreakpointEvent breakpointEvent;
        synchronized (context.getLock()) {
            breakpointEvent = context.getCurrentBreakpoint();
        }

        if (breakpointEvent == null)
            throw new NoBreakpointFoundException();

        Method method = breakpointEvent.location().method();
        if (method == null)
            throw new NoBreakpointFoundException("Breakpoint has no previous calls");

        Queue<Frame> returns = JvmContext.getContext().getReturns();
        String string = method.toString();
        for (Frame aReturn : returns) {
            String loc = aReturn.getCallerSignature();
            if (loc.equals(string))
                returnMap.put(aReturn.getResultLocation().method(), aReturn.getResult());
        }

        return ImmutableMap.copyOf(returnMap);
    }

    /**
     * Sets the current class reference type from given name.
     *
     * @param name the name of the reference to find
     * @param matchConsumer the consumer if references more than 0
     * @return JDB instance
     */
    public JDB setContext(String name, MultipleMatchConsumer matchConsumer) throws NoReferenceFoundException {
        ReferenceType type = getReference(name, matchConsumer);
        if (type != null)
            JvmContext.getContext().setCurrentRef(type);
        else
            throw new NoReferenceFoundException(name);

        return this;
    }

    /**
     * Sets the breakpoint for the current reference at line
     *
     * @param lineNumber the line of the reference to give a breakpoint
     * @return JDB instance
     */
    public JDB setBreakpoint(int lineNumber) throws NoReferenceFoundException {
        ReferenceType type = JvmContext.getContext().getCurrentRef();
        if (type == null)
            throw new NoReferenceFoundException();
        VirtualMachine vm = JvmContext.getContext().getVm();
        EventRequestManager manager = vm.eventRequestManager();

        try {
            for (Location location : type.locationsOfLine(lineNumber)) {
                BreakpointRequest req = manager.createBreakpointRequest(location);
                req.enable();
                JvmContext.getContext().getBreakpoints().put(location.sourceName() + ':' + lineNumber, req);

                Method method = location.method();
                JvmContext.getContext().getServer().write(new SignalOutReqMethod(location.declaringType().name(), method.name(), method.signature()));
                System.out.println("Breakpoint after " + type.name() + "." + method.name() + ":" + lineNumber);
            }
        } catch (AbsentInformationException exception) {
            exception.printStackTrace();
        }

        return this;
    }
}
