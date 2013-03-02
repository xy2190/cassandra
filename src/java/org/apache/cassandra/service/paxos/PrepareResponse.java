package org.apache.cassandra.service.paxos;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

import org.apache.cassandra.db.Row;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.utils.UUIDSerializer;

public class PrepareResponse
{
    public static final PrepareResponseSerializer serializer = new PrepareResponseSerializer();

    public final boolean promised;
    public final UUID mostRecentCommitted;
    public final UUID inProgressBallot;
    public final Row inProgressUpdates;

    public PrepareResponse(boolean promised, UUID mostRecentCommitted, UUID inProgressBallot, Row inProgressUpdates)
    {
        this.promised = promised;
        this.mostRecentCommitted = mostRecentCommitted;
        this.inProgressBallot = inProgressBallot;
        this.inProgressUpdates = inProgressUpdates;
    }

    public String toString()
    {
        return String.format("PrepareResponse(%s, %s, %s, %s)",
                             promised, mostRecentCommitted, inProgressBallot, inProgressUpdates);
    }

    public static class PrepareResponseSerializer implements IVersionedSerializer<PrepareResponse>
    {
        public void serialize(PrepareResponse response, DataOutput out, int version) throws IOException
        {
            out.writeBoolean(response.promised);
            UUIDSerializer.serializer.serialize(response.mostRecentCommitted, out, version);
            UUIDSerializer.serializer.serialize(response.inProgressBallot, out, version);
            out.writeBoolean(response.inProgressUpdates == null);
            if (response.inProgressUpdates != null)
                Row.serializer.serialize(response.inProgressUpdates, out, version);
        }

        public PrepareResponse deserialize(DataInput in, int version) throws IOException
        {
            return new PrepareResponse(in.readBoolean(),
                                       UUIDSerializer.serializer.deserialize(in, version),
                                       UUIDSerializer.serializer.deserialize(in, version),
                                       in.readBoolean() ? null : Row.serializer.deserialize(in, version));
        }

        public long serializedSize(PrepareResponse response, int version)
        {
            return 1
                   + UUIDSerializer.serializer.serializedSize(response.mostRecentCommitted, version)
                   + UUIDSerializer.serializer.serializedSize(response.inProgressBallot, version)
                   + (response.inProgressUpdates == null ? 1 : 1 +Row.serializer.serializedSize(response.inProgressUpdates, version));
        }
    }
}
