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
package com.gmail.woodyc40.topics.server;

import com.gmail.woodyc40.topics.protocol.*;
import com.google.common.collect.Queues;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.GuardedBy;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The local server handler
 */
public class AgentServer {

    /** The timeout for the server to shutdown */
    private static final long TIMEOUT_MS = 5000L;

    /** Currently waiting for connection */
    private static final int WAITING_FOR_CON = 0;
    /** Currently connected */
    private static final int ACTIVE = 1;
    /** Shutting down */
    private static final int SHUTDOWN = 2;

    /** The server that has been started */
    @Getter(AccessLevel.PRIVATE)
    private final ServerSocketChannel server;

    /** The current state of the server */
    @Getter
    private final AtomicInteger state = new AtomicInteger(WAITING_FOR_CON);
    /** Lock used to protect the connection */
    @Getter
    private final Lock lock = new ReentrantLock();
    /** Condition used to notify of a connection */
    @Getter
    private final Condition hasConnection = this.lock.newCondition();
    /** The signal send queue */
    @Getter
    private final BlockingQueue<SignalOut> out = Queues.newLinkedBlockingQueue();
    @Getter
    private final Queue<OutDataWrapper> outgoing = Queues.newConcurrentLinkedQueue();
    /** The signal process queue */
    @Getter
    private final BlockingQueue<InDataWrapper> incoming = Queues.newLinkedBlockingQueue();
    @Getter
    private final AtomicReference<Thread> duplex = new AtomicReference<>();
    /** The threads used for Net IO */
    private final Thread[] threads;
    /** The current client */
    @Getter
    @Setter
    @GuardedBy("lock")
    private SocketChannel conn;

    private static final Logger LOGGER = LogManager.getLogger(AgentServer.class);

    private AgentServer(int port, Thread[] threads) {
        this.threads = threads;
        try {
            this.server = ServerSocketChannel.open();
            this.server.bind(new InetSocketAddress(port));
            this.server.configureBlocking(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Initializes the server, which listens on the loopback
     * on the given port.
     *
     * @param port the port on which to start the server
     * @return the server object, used to send signals
     */
    public static AgentServer initServer(int port) {
        Thread[] threads = new Thread[4];
        AgentServer server = new AgentServer(port, threads);

        // Socket duplex
        threads[0] = new Thread(() -> {
            server.getDuplex().set(Thread.currentThread());

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    server.getLock().lockInterruptibly();
                    try {
                        while (server.getState().get() != AgentServer.ACTIVE) {
                            server.getHasConnection().await();
                        }

                        SocketChannel sock = server.getConn();
                        InputStream in = sock.socket().getInputStream();
                        OutputStream out = sock.socket().getOutputStream();
                        sock.socket().setSoTimeout(1000);

                        ByteArrayOutputStream headerAccum = new ByteArrayOutputStream();
                        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
                        byte[] header = new byte[4];
                        byte[] payload = new byte[1024];
                        int payloadLen = -1;
                        while (true) {
                            try {
                                // Payload length has been
                                // determined, read enough
                                // bytes into the buffer
                                // for the payload to be
                                // determined.
                                if (payloadLen != -1) {
                                    int len;
                                    while ((len = in.read(payload)) > -1) {
                                        accumulator.write(payload, 0, len);
                                    }
                                }

                                // Attempt to buffer the
                                // bytes in order to
                                // complete the header
                                int read = in.read(header);
                                if (read > -1) {
                                    headerAccum.write(header, 0, read);
                                }

                                // Header has been
                                // accumulated, switch state
                                // and write excess bytes to
                                // the payload
                                if (headerAccum.size() >= 4) {
                                    header = headerAccum.toByteArray();
                                    if (headerAccum.size() > 4) {
                                        for (int i = 4; i < header.length; i++) {
                                            accumulator.write(header[i]);
                                        }
                                    }

                                    payloadLen = (header[0] << 24) + (header[1] << 16) + (header[2] << 8) + (header[3] & 0xFF);
                                } else {
                                    continue;
                                }
                            } catch (SocketTimeoutException e) {
                                // Socket has not read
                                // anything, try to flush
                                // the outgoing signal queue
                                OutDataWrapper wrapper;
                                while ((wrapper = server.getOutgoing().poll()) != null) {
                                    byte[] buf = wrapper.getData();

                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    DataOutputStream dos = new DataOutputStream(baos);
                                    int v = buf.length + 4;

                                    dos.writeInt(v);
                                    dos.writeInt(wrapper.getId());

                                    out.write(baos.toByteArray());
                                    out.write(buf);
                                }
                            }

                            // Read in the payload data once
                            // the length has been
                            // determined and the data has
                            // filled the payload
                            // accumulator.
                            if (payloadLen != -1 && accumulator.size() >= payloadLen) {
                                ByteArrayInputStream input = new ByteArrayInputStream(accumulator.toByteArray());
                                DataInputStream stream = new DataInputStream(input);

                                int id = stream.readInt();
                                byte[] bs = new byte[payloadLen - 4];
                                stream.readFully(bs);
                                InDataWrapper wrapper = new InDataWrapper(bs, id);
                                server.getIncoming().add(wrapper);

                                payloadLen = -1;
                                headerAccum = new ByteArrayOutputStream();
                                accumulator = new ByteArrayOutputStream();

                                int len;
                                byte[] buf = new byte[1024];
                                while ((len = input.read(buf)) > -1) {
                                    accumulator.write(buf, 0, len);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (IOException exception) {
                        LOGGER.error("Disconnected due to ".concat(exception.getMessage()));
                        break;
                    } finally {
                        server.getLock().unlock();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // Write processor
        threads[1] = new Thread(() -> {
            while (true) {
                try {
                    SignalOut take = server.getOut().take();

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(out);
                    take.write(dos);

                    OutDataWrapper wrapper = new OutDataWrapper(out.toByteArray(), SignalRegistry.writeSignal(take));
                    server.getOutgoing().add(wrapper);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // Read processor
        threads[2] = new Thread(() -> {
            while (true) {
                try {
                    InDataWrapper data = server.getIncoming().take();
                    SignalIn signal = SignalRegistry.readSignal(data.getId());
                    signal.read(new DataInputStream(new ByteArrayInputStream(data.getData())));
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // Socket listener
        threads[3] = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SocketChannel ch = server.getServer().accept();
                    if (server.getState().compareAndSet(AgentServer.WAITING_FOR_CON, AgentServer.ACTIVE)) {
                        server.getLock().lockInterruptibly();
                        try {
                            server.setConn(ch);
                            server.getHasConnection().signalAll();
                        } finally {
                            server.getLock().unlock();
                        }
                    } else {
                        AgentServer.writeSignal(new SignalOutBusy(), ch);
                        ch.close();
                    }
                } catch (IOException | InterruptedException e) {
                    break;
                }
            }
        });

        return server;
    }

    /**
     * Writes the given signal out to the socket.
     *
     * @param sig the signal to write
     * @param ch the socket to which the signal will be
     * written
     * @throws IOException should not occur
     */
    private static void writeSignal(SignalOut sig, SocketChannel ch) throws IOException {
        int id = SignalRegistry.writeSignal(sig);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(out);
        sig.write(stream);

        int size = out.size() + 4;

        ByteBuffer buf = ByteBuffer.allocate(size + 4);
        buf.putInt(size);
        buf.putInt(id);
        buf.put(out.toByteArray());

        if (ch != null)
            ch.write(buf);
    }

    /**
     * Closes the server and halts processing of the packet
     * queue.
     */
    public void close() {
        this.state.set(AgentServer.SHUTDOWN);
        try {
            this.outgoing.clear();

            this.lock.lockInterruptibly();
            try {
                writeSignal(new SignalOutExit(3, "JDB Exit"), this.conn);
                this.conn.close();
            } finally {
                this.lock.unlock();
            }

            this.server.close();
            for (Thread thread : this.threads) {
                thread.interrupt();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Enqueues the given signal to be processed and written
     * out to the connection with the target VM.
     *
     * @param signal the signal to write
     */
    public void write(SignalOut signal) {
        this.out.add(signal);
    }
}
