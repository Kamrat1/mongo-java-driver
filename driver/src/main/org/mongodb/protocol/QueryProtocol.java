/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.protocol;

import org.mongodb.Decoder;
import org.mongodb.Document;
import org.mongodb.Encoder;
import org.mongodb.MongoFuture;
import org.mongodb.MongoNamespace;
import org.mongodb.MongoQueryFailureException;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.connection.BufferProvider;
import org.mongodb.connection.Connection;
import org.mongodb.connection.ConnectionReceiveArgs;
import org.mongodb.connection.PooledByteBufferOutputBuffer;
import org.mongodb.connection.ResponseBuffers;
import org.mongodb.connection.ServerDescription;
import org.mongodb.diagnostics.Loggers;
import org.mongodb.operation.Find;
import org.mongodb.operation.SingleResultFuture;
import org.mongodb.operation.SingleResultFutureCallback;
import org.mongodb.protocol.message.QueryMessage;
import org.mongodb.protocol.message.ReplyMessage;

import java.util.logging.Logger;

import static java.lang.String.format;
import static org.mongodb.protocol.ProtocolHelper.encodeMessageToBuffer;
import static org.mongodb.protocol.ProtocolHelper.getMessageSettings;

public class QueryProtocol<T> implements Protocol<QueryResult<T>> {

    public static final Logger LOGGER = Loggers.getLogger("protocol.query");

    private final Find find;
    private final Encoder<Document> queryEncoder;
    private final Decoder<T> resultDecoder;
    private final ServerDescription serverDescription;
    private final Connection connection;
    private boolean closeConnection;
    private final MongoNamespace namespace;
    private final BufferProvider bufferProvider;

    public QueryProtocol(final MongoNamespace namespace, final Find find, final Encoder<Document> queryEncoder,
                         final Decoder<T> resultDecoder, final BufferProvider bufferProvider,
                         final ServerDescription serverDescription, final Connection connection, final boolean closeConnection) {
        this.namespace = namespace;
        this.bufferProvider = bufferProvider;
        this.find = find;
        this.queryEncoder = queryEncoder;
        this.resultDecoder = resultDecoder;
        this.serverDescription = serverDescription;
        this.connection = connection;
        this.closeConnection = closeConnection;
    }

    @Override
    public QueryResult<T> execute() {
        try {
            LOGGER.fine(format("Sending query to namespace %s on connection [%s] to server %s", namespace, connection.getId(),
                    connection.getServerAddress()));
            QueryResult<T> queryResult = receiveMessage(sendMessage());
            LOGGER.fine("Query completed");
            return queryResult;
        } finally {
            if (closeConnection) {
                connection.close();
            }
        }
    }

    public MongoFuture<QueryResult<T>> executeAsync() {
        final SingleResultFuture<QueryResult<T>> retVal = new SingleResultFuture<QueryResult<T>>();

        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferProvider);
        final QueryMessage message = new QueryMessage(namespace.getFullName(), find, queryEncoder,
                getMessageSettings(serverDescription));
        encodeMessageToBuffer(message, buffer);
        connection.sendMessageAsync(buffer.getByteBuffers(),
                new SendMessageCallback<QueryResult<T>>(connection, buffer, message.getId(), retVal,
                        new QueryResultCallback<T>(
                                new SingleResultFutureCallback<QueryResult<T>>(retVal), resultDecoder, message.getId(), connection,
                                closeConnection)));
        return retVal;

    }

    private QueryMessage sendMessage() {
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(bufferProvider);
        try {
            QueryMessage message = new QueryMessage(namespace.getFullName(), find, queryEncoder,
                    getMessageSettings(serverDescription));
            message.encode(buffer);
            connection.sendMessage(buffer.getByteBuffers());
            return message;
        } finally {
            buffer.close();
        }
    }

    private QueryResult<T> receiveMessage(final QueryMessage message) {
        final ResponseBuffers responseBuffers = connection.receiveMessage(
                new ConnectionReceiveArgs(message.getId()));
        try {
            if (responseBuffers.getReplyHeader().isQueryFailure()) {
                final Document errorDocument =
                        new ReplyMessage<Document>(responseBuffers, new DocumentCodec(), message.getId()).getDocuments().get(0);
                throw new MongoQueryFailureException(connection.getServerAddress(), errorDocument);
            }
            final ReplyMessage<T> replyMessage = new ReplyMessage<T>(responseBuffers, resultDecoder, message.getId());

            return new QueryResult<T>(replyMessage, connection.getServerAddress());
        } finally {
            responseBuffers.close();
        }
    }
}