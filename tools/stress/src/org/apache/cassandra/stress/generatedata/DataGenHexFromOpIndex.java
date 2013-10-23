package org.apache.cassandra.stress.generatedata;

public class DataGenHexFromOpIndex extends DataGenHex
{

    final long minKey;
    final long maxKey;

    public DataGenHexFromOpIndex(long minKey, long maxKey)
    {
        this.minKey = minKey;
        this.maxKey = maxKey;
    }

    @Override
    public boolean deterministic()
    {
        return true;
    }

    @Override
    long nextInt(long operationIndex)
    {
        long range = maxKey + 1 - minKey;
        return Math.abs((operationIndex % range) + minKey);
    }
}
