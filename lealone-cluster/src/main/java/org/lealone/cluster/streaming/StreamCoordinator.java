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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.streaming;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.lealone.cluster.concurrent.DebuggableThreadPoolExecutor;
import org.lealone.cluster.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StreamCoordinator} is a helper class that abstracts away maintaining multiple
 * StreamSession and ProgressInfo instances per peer.
 *
 * This class coordinates multiple SessionStreams per peer in both the outgoing StreamPlan context and on the
 * inbound StreamResultFuture context.
 */
public class StreamCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(StreamCoordinator.class);

    // Executor strictly for establishing the initial connections. Once we're connected to the other end the rest of the
    // streaming is handled directly by the ConnectionHandler's incoming and outgoing threads.
    private static final DebuggableThreadPoolExecutor streamExecutor = DebuggableThreadPoolExecutor
            .createWithFixedPoolSize("StreamConnectionEstablisher", Utils.getAvailableProcessors());

    private final Map<InetAddress, HostStreamingData> peerSessions = new HashMap<>();
    private final int connectionsPerHost;
    private StreamConnectionFactory factory;

    public StreamCoordinator(int connectionsPerHost, StreamConnectionFactory factory) {
        this.connectionsPerHost = connectionsPerHost;
        this.factory = factory;
    }

    public void setConnectionFactory(StreamConnectionFactory factory) {
        this.factory = factory;
    }

    /**
     * @return true if any stream session is active
     */
    public synchronized boolean hasActiveSessions() {
        for (HostStreamingData data : peerSessions.values()) {
            if (data.hasActiveSessions())
                return true;
        }
        return false;
    }

    public synchronized Collection<StreamSession> getAllStreamSessions() {
        Collection<StreamSession> results = new ArrayList<>();
        for (HostStreamingData data : peerSessions.values()) {
            results.addAll(data.getAllStreamSessions());
        }
        return results;
    }

    public boolean isReceiving() {
        return connectionsPerHost == 0;
    }

    public void connectAllStreamSessions() {
        for (HostStreamingData data : peerSessions.values())
            data.connectAllStreamSessions();
    }

    public synchronized Set<InetAddress> getPeers() {
        return new HashSet<>(peerSessions.keySet());
    }

    public synchronized StreamSession getOrCreateNextSession(InetAddress peer, InetAddress connecting) {
        return getOrCreateHostData(peer).getOrCreateNextSession(peer, connecting);
    }

    public synchronized StreamSession getOrCreateSessionById(InetAddress peer, int id, InetAddress connecting) {
        return getOrCreateHostData(peer).getOrCreateSessionById(peer, id, connecting);
    }

    public synchronized void updateProgress(ProgressInfo info) {
        getHostData(info.peer).updateProgress(info);
    }

    public synchronized void addSessionInfo(SessionInfo session) {
        HostStreamingData data = getOrCreateHostData(session.peer);
        data.addSessionInfo(session);
    }

    public synchronized Set<SessionInfo> getAllSessionInfo() {
        Set<SessionInfo> result = new HashSet<>();
        for (HostStreamingData data : peerSessions.values()) {
            result.addAll(data.getAllSessionInfo());
        }
        return result;
    }

    private HostStreamingData getHostData(InetAddress peer) {
        HostStreamingData data = peerSessions.get(peer);
        if (data == null)
            throw new IllegalArgumentException("Unknown peer requested: " + peer);
        return data;
    }

    private HostStreamingData getOrCreateHostData(InetAddress peer) {
        HostStreamingData data = peerSessions.get(peer);
        if (data == null) {
            data = new HostStreamingData();
            peerSessions.put(peer, data);
        }
        return data;
    }

    private static class StreamSessionConnector implements Runnable {
        private final StreamSession session;

        public StreamSessionConnector(StreamSession session) {
            this.session = session;
        }

        @Override
        public void run() {
            session.start();
            logger.info("[Stream #{}, ID#{}] Beginning stream session with {}", session.planId(),
                    session.sessionIndex(), session.peer);
        }
    }

    private class HostStreamingData {
        private final Map<Integer, StreamSession> streamSessions = new HashMap<>();
        private final Map<Integer, SessionInfo> sessionInfos = new HashMap<>();

        private int lastReturned = -1;

        public boolean hasActiveSessions() {
            for (StreamSession session : streamSessions.values()) {
                StreamSession.State state = session.state();
                if (state != StreamSession.State.COMPLETE && state != StreamSession.State.FAILED)
                    return true;
            }
            return false;
        }

        public StreamSession getOrCreateNextSession(InetAddress peer, InetAddress connecting) {
            // create
            if (streamSessions.size() < connectionsPerHost) {
                StreamSession session = new StreamSession(peer, connecting, factory, streamSessions.size());
                streamSessions.put(++lastReturned, session);
                return session;
            }
            // get
            else {
                if (lastReturned >= streamSessions.size() - 1)
                    lastReturned = 0;

                return streamSessions.get(lastReturned++);
            }
        }

        public void connectAllStreamSessions() {
            for (StreamSession session : streamSessions.values()) {
                streamExecutor.execute(new StreamSessionConnector(session));
            }
        }

        public Collection<StreamSession> getAllStreamSessions() {
            return Collections.unmodifiableCollection(streamSessions.values());
        }

        public StreamSession getOrCreateSessionById(InetAddress peer, int id, InetAddress connecting) {
            StreamSession session = streamSessions.get(id);
            if (session == null) {
                session = new StreamSession(peer, connecting, factory, id);
                streamSessions.put(id, session);
            }
            return session;
        }

        public void updateProgress(ProgressInfo info) {
            sessionInfos.get(info.sessionIndex).updateProgress(info);
        }

        public void addSessionInfo(SessionInfo info) {
            sessionInfos.put(info.sessionIndex, info);
        }

        public Collection<SessionInfo> getAllSessionInfo() {
            return sessionInfos.values();
        }
    }
}
