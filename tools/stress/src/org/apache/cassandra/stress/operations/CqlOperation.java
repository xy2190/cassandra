/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.stress.operations;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.*;
import com.google.common.collect.*;
import org.apache.cassandra.stress.Operation;
import org.apache.cassandra.stress.settings.ConnectionAPI;
import org.apache.cassandra.stress.settings.CqlVersion;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.transport.SimpleClient;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.thrift.Compression;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.ThriftConversion;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Hex;
import org.apache.thrift.TException;

public abstract class CqlOperation<V> extends Operation
{

    protected abstract List<ByteBuffer> getQueryParameters(byte[] key);
    protected abstract String buildQuery();
    protected abstract CqlRunOp<V> buildRunOp(ClientWrapper client, String query, byte[] queryId, List<ByteBuffer> params, String key);

    public CqlOperation(State state, long idx)
    {
        super(state, idx);
        if (state.settings.columns.useSuperColumns)
            throw new IllegalStateException("Super columns are not implemented for CQL");
        if (state.settings.columns.variableColumnCount)
            throw new IllegalStateException("Variable column counts are not implemented for CQL");
    }

    protected CqlRunOp<V> run(final ClientWrapper client, final List<ByteBuffer> queryParams, final String key) throws IOException
    {
        final CqlRunOp<V> op;
        if (state.settings.mode.api == ConnectionAPI.CQL_PREPARED)
        {
            final byte[] id;
            Object idobj = state.getCqlCache();
            if (idobj == null)
            {
                try
                {
                    id = client.createPreparedStatement(buildQuery());
                } catch (TException e)
                {
                    throw new RuntimeException(e);
                }
                state.storeCqlCache(id);
            }
            else
                id = (byte[]) idobj;

            op = buildRunOp(client, null, id, queryParams, key);
        }
        else
        {
            final String query;
            Object qobj = state.getCqlCache();
            if (qobj == null)
                state.storeCqlCache(query = buildQuery());
            else
                query = qobj.toString();

            op = buildRunOp(client, query, null, queryParams, key);
        }

        timeWithRetry(op);
        return op;
    }

    protected void run(final ClientWrapper client) throws IOException
    {
        final byte[] key = getKey().array();
        final List<ByteBuffer> queryParams = getQueryParameters(key);
        run(client, queryParams, new String(key));
    }

    // Classes to process Cql results

    // Always succeeds so long as the query executes without error; provides a keyCount to increment on instantiation
    protected final class CqlRunOpAlwaysSucceed extends CqlRunOp<Integer>
    {

        final int keyCount;

        protected CqlRunOpAlwaysSucceed(ClientWrapper client, String query, byte[] queryId, List<ByteBuffer> params, String key, int keyCount)
        {
            super(client, query, queryId, RowCountHandler.INSTANCE, params, key);
            this.keyCount = keyCount;
        }

        @Override
        public boolean validate(Integer result)
        {
            return true;
        }

        @Override
        public int keyCount()
        {
            return keyCount;
        }
    }

    // Succeeds so long as the result set is nonempty, and the query executes without error
    protected final class CqlRunOpTestNonEmpty extends CqlRunOp<Integer>
    {

        protected CqlRunOpTestNonEmpty(ClientWrapper client, String query, byte[] queryId, List<ByteBuffer> params, String key)
        {
            super(client, query, queryId, RowCountHandler.INSTANCE, params, key);
        }

        @Override
        public boolean validate(Integer result)
        {
            return true;
        }

        @Override
        public int keyCount()
        {
            return result;
        }
    }

    // Requires a custom validate() method, but fetches and stores the keys from the result set for further processing
    protected abstract class CqlRunOpFetchKeys extends CqlRunOp<byte[][]>
    {

        protected CqlRunOpFetchKeys(ClientWrapper client, String query, byte[] queryId, List<ByteBuffer> params, String key)
        {
            super(client, query, queryId, KeysHandler.INSTANCE, params, key);
        }

        @Override
        public int keyCount()
        {
            return result.length;
        }

    }

    // Cql
    protected abstract class CqlRunOp<V> implements RunOp
    {

        final ClientWrapper client;
        final String query;
        final byte[] queryId;
        final List<ByteBuffer> params;
        final String key;
        final ResultHandler<V> handler;
        V result;

        private CqlRunOp(ClientWrapper client, String query, byte[] queryId, ResultHandler<V> handler, List<ByteBuffer> params, String key)
        {
            this.client = client;
            this.query = query;
            this.queryId = queryId;
            this.handler = handler;
            this.params = params;
            this.key = key;
        }

        @Override
        public boolean run() throws Exception
        {
            return queryId != null
            ? validate(result = client.execute(queryId, params, handler))
            : validate(result = client.execute(query, params, handler));
        }

        @Override
        public String key()
        {
            return key;
        }

        public abstract boolean validate(V result);

    }


    /// LOTS OF WRAPPING/UNWRAPPING NONSENSE


    public void run(final Cassandra.Client client) throws IOException
    {
        run(wrap(client));
    }

    @Override
    public void run(SimpleClient client) throws IOException
    {
        run(wrap(client));
    }

    public ClientWrapper wrap(Cassandra.Client client)
    {
        return state.isCql3()
                ? new Cql3CassandraClientWrapper(client)
                : new Cql2CassandraClientWrapper(client);

    }

    public ClientWrapper wrap(SimpleClient client)
    {
        return new SimpleClientWrapper(client);
    }

    protected interface ClientWrapper
    {
        byte[] createPreparedStatement(String cqlQuery) throws TException;
        <V> V execute(byte[] preparedStatementId, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException;
        <V> V execute(String query, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException;
    }

    private final class SimpleClientWrapper implements ClientWrapper
    {
        final SimpleClient client;
        private SimpleClientWrapper(SimpleClient client)
        {
            this.client = client;
        }

        @Override
        public <V> V execute(String query, List<ByteBuffer> queryParams, ResultHandler<V> handler)
        {
            String formattedQuery = formatCqlQuery(query, queryParams, state.isCql3());
            return handler.thriftHandler().apply(client.execute(formattedQuery, ThriftConversion.fromThrift(state.settings.command.consistencyLevel)));
        }

        @Override
        public <V> V execute(byte[] preparedStatementId, List<ByteBuffer> queryParams, ResultHandler<V> handler)
        {
            return handler.thriftHandler().apply(
                    client.executePrepared(
                            preparedStatementId,
                            queryParams,
                            ThriftConversion.fromThrift(state.settings.command.consistencyLevel)));
        }

        @Override
        public byte[] createPreparedStatement(String cqlQuery)
        {
            return client.prepare(cqlQuery).statementId.bytes;
        }
    }

    // client wrapper for Cql3
    private final class Cql3CassandraClientWrapper implements ClientWrapper
    {
        final Cassandra.Client client;
        private Cql3CassandraClientWrapper(Cassandra.Client client)
        {
            this.client = client;
        }

        @Override
        public <V> V execute(String query, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException
        {
            String formattedQuery = formatCqlQuery(query, queryParams, true);
            return handler.cqlHandler().apply(
                    client.execute_cql3_query(ByteBuffer.wrap(formattedQuery.getBytes()), Compression.NONE, state.settings.command.consistencyLevel)
            );
        }

        @Override
        public <V> V execute(byte[] preparedStatementId, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException
        {
            Integer id = fromBytes(preparedStatementId);
            return handler.cqlHandler().apply(
                    client.execute_prepared_cql3_query(id, queryParams, state.settings.command.consistencyLevel)
            );
        }

        @Override
        public byte[] createPreparedStatement(String cqlQuery) throws TException
        {
            return toBytes(client.prepare_cql3_query(ByteBufferUtil.bytes(cqlQuery), Compression.NONE).itemId);
        }
    }

    // client wrapper for Cql2
    private final class Cql2CassandraClientWrapper implements ClientWrapper
    {
        final Cassandra.Client client;
        private Cql2CassandraClientWrapper(Cassandra.Client client)
        {
            this.client = client;
        }

        @Override
        public <V> V execute(String query, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException
        {
            String formattedQuery = formatCqlQuery(query, queryParams, false);
            return handler.cqlHandler().apply(
                    client.execute_cql_query(ByteBuffer.wrap(formattedQuery.getBytes()), Compression.NONE)
            );
        }

        @Override
        public <V> V execute(byte[] preparedStatementId, List<ByteBuffer> queryParams, ResultHandler<V> handler) throws TException
        {
            Integer id = fromBytes(preparedStatementId);
            return handler.cqlHandler().apply(
                    client.execute_prepared_cql_query(id, queryParams)
            );
        }

        @Override
        public byte[] createPreparedStatement(String cqlQuery) throws TException
        {
            return toBytes(client.prepare_cql_query(ByteBufferUtil.bytes(cqlQuery), Compression.NONE).itemId);
        }
    }

    // interface for building functions to standardise results from each client
    protected static interface ResultHandler<V>
    {
        Function<ResultMessage, V> thriftHandler();
        Function<CqlResult, V> cqlHandler();
    }

    protected static class RowCountHandler implements ResultHandler<Integer>
    {
        static final RowCountHandler INSTANCE = new RowCountHandler();
        @Override
        public Function<ResultMessage, Integer> thriftHandler()
        {
            return new Function<ResultMessage, Integer>()
            {
                @Override
                public Integer apply(ResultMessage result)
                {
                    return result instanceof ResultMessage.Rows ? ((ResultMessage.Rows) result).result.size() : 0;
                }
            };
        }

        @Override
        public Function<CqlResult, Integer> cqlHandler()
        {
            return new Function<CqlResult, Integer>()
            {

                @Override
                public Integer apply(CqlResult result)
                {
                    switch (result.getType())
                    {
                        case ROWS:
                            return result.getRows().size();
                        default:
                            return 1;
                    }
                }
            };
        }

    }

    // Processes results from each client into an array of all key bytes returned
    protected static final class KeysHandler implements ResultHandler<byte[][]>
    {
        static final KeysHandler INSTANCE = new KeysHandler();

        @Override
        public Function<ResultMessage, byte[][]> thriftHandler()
        {
            return new Function<ResultMessage, byte[][]>()
            {

                @Override
                public byte[][] apply(ResultMessage result)
                {
                    if (result instanceof ResultMessage.Rows)
                    {
                        ResultMessage.Rows rows = ((ResultMessage.Rows) result);
                        byte[][] r = new byte[rows.result.size()][];
                        for (int i = 0 ; i < r.length ; i++)
                            r[i] = rows.result.rows.get(i).get(0).array();
                        return r;
                    }
                    return null;
                }
            };
        }

        @Override
        public Function<CqlResult, byte[][]> cqlHandler()
        {
            return new Function<CqlResult, byte[][]>()
            {

                @Override
                public byte[][] apply(CqlResult result)
                {
                    byte[][] r = new byte[result.getRows().size()][];
                    for (int i = 0 ; i < r.length ; i++)
                        r[i] = result.getRows().get(i).getKey();
                    return r;
                }
            };
        }

    }

    private static Integer fromBytes(byte[] bytes)
    {
        return (bytes[0] & 0xFF)
             | ((bytes[1] & 0xFF) << 8)
             | ((bytes[2] & 0xFF) << 16)
             | ((bytes[3] & 0xFF) << 24);
    }

    private static byte[] toBytes(int integer)
    {
        return new byte[] {
                (byte)(integer & 0xFF),
                (byte)((integer >>> 8) & 0xFF),
                (byte) ((integer >>> 16) & 0xFF),
                (byte) ((integer >>> 24) & 0xFF)
        };
    }

    private static String getUnQuotedCqlBlob(ByteBuffer term, boolean isCQL3)
    {
        return isCQL3
                ? "0x" + ByteBufferUtil.bytesToHex(term)
                : ByteBufferUtil.bytesToHex(term);
    }

    /**
     * Constructs a CQL query string by replacing instances of the character
     * '?', with the corresponding parameter.
     *
     * @param query base query string to format
     * @param parms sequence of string query parameters
     * @return formatted CQL query string
     */
    private static String formatCqlQuery(String query, List<ByteBuffer> parms, boolean isCql3)
    {
        int marker, position = 0;
        StringBuilder result = new StringBuilder();

        if (-1 == (marker = query.indexOf('?')) || parms.size() == 0)
            return query;

        for (ByteBuffer parm : parms)
        {
            result.append(query.substring(position, marker));
            result.append(getUnQuotedCqlBlob(parm, isCql3));

            position = marker + 1;
            if (-1 == (marker = query.indexOf('?', position + 1)))
                break;
        }

        if (position < query.length())
            result.append(query.substring(position));

        return result.toString();
    }

    protected String wrapInQuotesIfRequired(String string)
    {
        return state.settings.mode.cqlVersion == CqlVersion.CQL3
                ? "\"" + string + "\""
                : string;
    }

}
