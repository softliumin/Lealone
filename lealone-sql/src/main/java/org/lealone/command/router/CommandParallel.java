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
package org.lealone.command.router;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.command.CommandInterface;
import org.lealone.command.FrontendCommand;
import org.lealone.command.dml.Select;
import org.lealone.engine.Session;
import org.lealone.message.DbException;
import org.lealone.result.ResultInterface;
import org.lealone.util.New;

public class CommandParallel {
    private final static ThreadPoolExecutor pool = initPool();

    static class NamedThreadFactory implements ThreadFactory {
        protected final String id;
        private final int priority;
        protected final AtomicInteger n = new AtomicInteger(1);

        public NamedThreadFactory(String id) {
            this(id, Thread.NORM_PRIORITY);
        }

        public NamedThreadFactory(String id, int priority) {

            this.id = id;
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            String name = id + ":" + n.getAndIncrement();
            Thread thread = new Thread(runnable, name);
            thread.setPriority(priority);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static ThreadPoolExecutor initPool() {
        //        int corePoolSize = HBaseUtils.getConfiguration().getInt(COMMAND_PARALLEL_CORE_POOL_SIZE,
        //                DEFAULT_COMMAND_PARALLEL_CORE_POOL_SIZE);
        //        int maxPoolSize = HBaseUtils.getConfiguration().getInt(COMMAND_PARALLEL_MAX_POOL_SIZE,
        //                DEFAULT_COMMAND_PARALLEL_MAX_POOL_SIZE);
        //        int keepAliveTime = HBaseUtils.getConfiguration().getInt(COMMAND_PARALLEL_KEEP_ALIVE_TIME,
        //                DEFAULT_COMMAND_PARALLEL_KEEP_ALIVE_TIME);

        int corePoolSize = 3;
        int maxPoolSize = Integer.MAX_VALUE;
        int keepAliveTime = 3;

        ThreadPoolExecutor pool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new NamedThreadFactory(CommandParallel.class.getSimpleName()));
        pool.allowCoreThreadTimeOut(true);

        return pool;
    }

    public static ThreadPoolExecutor getThreadPoolExecutor() {
        return pool;
    }

    public static String getPlanSQL(Select select) {
        if (select.isGroupQuery() || select.getLimit() != null)
            return select.getPlanSQL(true);
        else
            return select.getSQL();
    }

    public static ResultInterface executeQuery(Session session, SQLRoutingInfo sqlRoutingInfo, Select select, final int maxRows,
            final boolean scrollable) {

        List<CommandInterface> commands = new ArrayList<CommandInterface>();
        if (sqlRoutingInfo.remoteCommands != null) {
            commands.addAll(sqlRoutingInfo.remoteCommands);
        }
        //        if (sqlRoutingInfo.localRegions != null) {
        //            for (String regionName : sqlRoutingInfo.localRegions) {
        //                Prepared p = session.prepare(getPlanSQL(select), true);
        //                p.setExecuteDirec(true);
        //                p.setFetchSize(select.getFetchSize());
        //                if (p instanceof WithWhereClause) {
        //                    ((WithWhereClause) p).getWhereClauseSupport().setRegionName(regionName);
        //                }
        //                commands.add(new CommandWrapper(p));
        //            }
        //        }
        //originalSelect.isGroupQuery()如果是false，那么按org.apache.hadoop.hbase.client.ClientScanner的功能来实现。
        //只要Select语句中出现聚合函数、groupBy、Having三者之一都被认为是GroupQuery，
        //对于GroupQuery需要把Select语句同时发给相关的RegionServer，得到结果后再合并。
        if (!select.isGroupQuery() && select.getSortOrder() == null)
            return new SerializedResult(commands, maxRows, scrollable, select);

        int size = commands.size();
        List<Future<ResultInterface>> futures = New.arrayList(size);
        List<ResultInterface> results = New.arrayList(size);
        for (int i = 0; i < size; i++) {
            final CommandInterface c = commands.get(i);
            futures.add(pool.submit(new Callable<ResultInterface>() {
                @Override
                public ResultInterface call() throws Exception {
                    return c.executeQuery(maxRows, scrollable);
                }
            }));
        }
        try {
            for (int i = 0; i < size; i++) {
                results.add(futures.get(i).get());
            }
        } catch (Exception e) {
            throwException(e);
        }

        if (!select.isGroupQuery() && select.getSortOrder() != null)
            return new SortedResult(maxRows, session, select, results);

        String newSQL = select.getPlanSQL(true);
        Select newSelect = (Select) session.prepare(newSQL, true);
        newSelect.setExecuteDirec(true);

        return new MergedResult(results, newSelect, select);
    }

    public static int executeUpdate(List<CommandInterface> commands) {
        if (commands.size() == 1) {
            CommandInterface c = commands.get(0);
            return c.executeUpdate();
        }
        int updateCount = 0;
        int size = commands.size();
        List<Future<Integer>> futures = New.arrayList(size);
        for (int i = 0; i < size; i++) {
            final CommandInterface c = commands.get(i);
            futures.add(pool.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    return c.executeUpdate();
                }
            }));
        }
        try {
            for (int i = 0; i < size; i++) {
                updateCount += futures.get(i).get();
            }
        } catch (Exception e) {
            throwException(e);
        }
        return updateCount;
    }

    public static int executeUpdate(SQLRoutingInfo sqlRoutingInfo, Callable<Integer> call) {
        int updateCount = 0;
        List<FrontendCommand> commands = sqlRoutingInfo.remoteCommands;
        int size = commands.size() + 1;
        List<Future<Integer>> futures = New.arrayList(size);
        futures.add(pool.submit(call));
        for (int i = 0; i < size - 1; i++) {
            final CommandInterface c = commands.get(i);
            futures.add(pool.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    return c.executeUpdate();
                }
            }));
        }
        try {
            for (int i = 0; i < size; i++) {
                updateCount += futures.get(i).get();
            }
        } catch (Exception e) {
            throwException(e);
        }
        return updateCount;
    }

    public static int executeUpdateCallable(List<Callable<Integer>> calls) {
        int size = calls.size();
        List<Future<Integer>> futures = New.arrayList(size);
        for (int i = 0; i < size; i++) {
            futures.add(pool.submit(calls.get(i)));
        }
        int updateCount = 0;
        try {
            for (int i = 0; i < size; i++) {
                updateCount += futures.get(i).get();
            }
        } catch (Exception e) {
            throwException(e);
        }
        return updateCount;
    }

    public static <T> void execute(List<Callable<T>> calls) {
        int size = calls.size();
        List<Future<T>> futures = New.arrayList(size);
        for (int i = 0; i < size; i++) {
            futures.add(pool.submit(calls.get(i)));
        }
        try {
            for (int i = 0; i < size; i++) {
                futures.get(i).get();
            }
        } catch (Exception e) {
            throwException(e);
        }
    }

    private static void throwException(Throwable e) {
        if (e instanceof ExecutionException)
            e = ((ExecutionException) e).getCause();
        throw DbException.convert(e);
    }
}