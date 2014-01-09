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
package org.apache.cassandra.db;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;

import org.apache.cassandra.utils.memory.AbstractAllocator;
import org.apache.cassandra.utils.memory.HeapAllocator;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import org.apache.cassandra.cache.IRowCacheEntry;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.composites.CellNames;
import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.io.sstable.ColumnNameHelper;
import org.apache.cassandra.io.sstable.ColumnStats;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.*;

/**
 * A sorted map of columns.
 * This represents the backing map of a colum family.
 *
 * Whether the implementation is thread safe or not is left to the
 * implementing classes.
 */
public abstract class ColumnFamily implements Iterable<Cell>, IRowCacheEntry
{
    /* The column serializer for this Column Family. Create based on config. */
    public static final ColumnFamilySerializer serializer = new ColumnFamilySerializer();

    protected final CFMetaData metadata;

    protected ColumnFamily(CFMetaData metadata)
    {
        assert metadata != null;
        this.metadata = metadata;
    }

    public <T extends ColumnFamily> T cloneMeShallow(ColumnFamily.Factory<T> factory, boolean reversedInsertOrder)
    {
        T cf = factory.create(metadata, reversedInsertOrder);
        cf.delete(this);
        return cf;
    }

    public ColumnFamily cloneMeShallow()
    {
        return cloneMeShallow(getFactory(), isInsertReversed());
    }

    public ColumnFamilyType getType()
    {
        return metadata.cfType;
    }

    /**
     * Clones the column map.
     */
    public abstract ColumnFamily cloneMe();

    public UUID id()
    {
        return metadata.cfId;
    }

    /**
     * @return The CFMetaData for this row
     */
    public CFMetaData metadata()
    {
        return metadata;
    }

    public void addIfRelevant(Cell cell, DeletionInfo.InOrderTester tester, int gcBefore)
    {
        // the cell itself must be not gc-able (it is live, or a still relevant tombstone), (1)
        // and if its container is deleted, the cell must be changed more recently than the container tombstone (2)
        if ((cell.getLocalDeletionTime() >= gcBefore) // (1)
            && (!tester.isDeleted(cell.name(), cell.timestamp())))                                // (2)
        {
            addColumn(cell);
        }
    }

    public void addColumn(Cell cell)
    {
        addColumn(cell, HeapAllocator.instance);
    }

    public void addColumn(CellName name, ByteBuffer value, long timestamp)
    {
        addColumn(name, value, timestamp, 0);
    }

    public void addColumn(CellName name, ByteBuffer value, long timestamp, int timeToLive)
    {
        assert !metadata().getDefaultValidator().isCommutative();
        Cell cell = Cell.create(name, value, timestamp, timeToLive, metadata());
        addColumn(cell);
    }

    public void addCounter(CellName name, long value)
    {
        addColumn(new CounterUpdateCell(name, value, System.currentTimeMillis()));
    }

    public void addTombstone(CellName name, ByteBuffer localDeletionTime, long timestamp)
    {
        addColumn(new DeletedCell(name, localDeletionTime, timestamp));
    }

    public void addTombstone(CellName name, int localDeletionTime, long timestamp)
    {
        addColumn(new DeletedCell(name, localDeletionTime, timestamp));
    }

    public void addAtom(OnDiskAtom atom)
    {
        if (atom instanceof Cell)
        {
            addColumn((Cell)atom);
        }
        else
        {
            assert atom instanceof RangeTombstone;
            delete((RangeTombstone)atom);
        }
    }

    /**
     * Clear this column family, removing all columns and deletion info.
     */
    public abstract void clear();

    /**
     * Returns a {@link DeletionInfo.InOrderTester} for the deletionInfo() of
     * this column family. Please note that for ThreadSafe implementation of ColumnFamily,
     * this tester will remain valid even if new tombstones are added to this ColumnFamily
     * *as long as said addition is done in comparator order*. For AtomicSortedColumns,
     * the tester will correspond to the state of when this method is called.
     */
    public DeletionInfo.InOrderTester inOrderDeletionTester()
    {
        return deletionInfo().inOrderTester();
    }

    /**
     * Returns the factory used for this ISortedColumns implementation.
     */
    public abstract Factory getFactory();

    public abstract DeletionInfo deletionInfo();
    public abstract void setDeletionInfo(DeletionInfo info);

    public abstract void delete(DeletionInfo info);
    public abstract void delete(DeletionTime deletionTime);
    protected abstract void delete(RangeTombstone tombstone);

    /**
     * Purges top-level and range tombstones whose localDeletionTime is older than gcBefore.
     * @param gcBefore a timestamp (in seconds) before which tombstones should be purged
     */
    public abstract void purgeTombstones(int gcBefore);

    /**
     * Adds a cell to this cell map.
     * If a cell with the same name is already present in the map, it will
     * be replaced by the newly added cell.
     */
    public abstract void addColumn(Cell cell, AbstractAllocator allocator);

    /**
     * Adds all the columns of a given column map to this column map.
     * This is equivalent to:
     *   <code>
     *   for (Cell c : cm)
     *      addColumn(c, ...);
     *   </code>
     *  but is potentially faster.
     */
    public abstract void addAll(ColumnFamily cm, AbstractAllocator allocator, Function<Cell, Cell> transformation);

    /**
     * Replace oldCell if present by newCell.
     * Returns true if oldCell was present and thus replaced.
     * oldCell and newCell should have the same name.
     */
    public abstract boolean replace(Cell oldCell, Cell newCell);

    /**
     * Get a column given its name, returning null if the column is not
     * present.
     */
    public abstract Cell getColumn(CellName name);

    /**
     * Returns an iterable with the names of columns in this column map in the same order
     * as the underlying columns themselves.
     */
    public abstract Iterable<CellName> getColumnNames();

    /**
     * Returns the columns of this column map as a collection.
     * The columns in the returned collection should be sorted as the columns
     * in this map.
     */
    public abstract Collection<Cell> getSortedColumns();

    /**
     * Returns the columns of this column map as a collection.
     * The columns in the returned collection should be sorted in reverse
     * order of the columns in this map.
     */
    public abstract Collection<Cell> getReverseSortedColumns();

    /**
     * Returns the number of columns in this map.
     */
    public abstract int getColumnCount();

    /**
     * Returns true if this contains no columns or deletion info
     */
    public boolean isEmpty()
    {
        return deletionInfo().isLive() && getColumnCount() == 0;
    }

    /**
     * Returns an iterator over the columns of this map that returns only the matching @param slices.
     * The provided slices must be in order and must be non-overlapping.
     */
    public abstract Iterator<Cell> iterator(ColumnSlice[] slices);

    /**
     * Returns a reversed iterator over the columns of this map that returns only the matching @param slices.
     * The provided slices must be in reversed order and must be non-overlapping.
     */
    public abstract Iterator<Cell> reverseIterator(ColumnSlice[] slices);

    /**
     * Returns if this map only support inserts in reverse order.
     */
    public abstract boolean isInsertReversed();

    /**
     * If `columns` has any tombstones (top-level or range tombstones), they will be applied to this set of columns.
     */
    public void delete(ColumnFamily columns)
    {
        delete(columns.deletionInfo());
    }

    public void addAll(ColumnFamily cf, AbstractAllocator allocator)
    {
        addAll(cf, allocator, Functions.<Cell>identity());
    }

    /*
     * This function will calculate the difference between 2 column families.
     * The external input is assumed to be a superset of internal.
     */
    public ColumnFamily diff(ColumnFamily cfComposite)
    {
        assert cfComposite.id().equals(id());
        ColumnFamily cfDiff = TreeMapBackedSortedColumns.factory.create(metadata);
        cfDiff.delete(cfComposite.deletionInfo());

        // (don't need to worry about cfNew containing Columns that are shadowed by
        // the delete tombstone, since cfNew was generated by CF.resolve, which
        // takes care of those for us.)
        for (Cell cellExternal : cfComposite)
        {
            CellName cName = cellExternal.name();
            Cell cellInternal = getColumn(cName);
            if (cellInternal == null)
            {
                cfDiff.addColumn(cellExternal);
            }
            else
            {
                Cell cellDiff = cellInternal.diff(cellExternal);
                if (cellDiff != null)
                {
                    cfDiff.addColumn(cellDiff);
                }
            }
        }

        if (!cfDiff.isEmpty())
            return cfDiff;
        return null;
    }

    public long dataSize()
    {
        long size = 0;
        for (Cell cell : this)
            size += cell.dataSize();
        return size;
    }

    public long maxTimestamp()
    {
        long maxTimestamp = deletionInfo().maxTimestamp();
        for (Cell cell : this)
            maxTimestamp = Math.max(maxTimestamp, cell.maxTimestamp());
        return maxTimestamp;
    }

    @Override
    public int hashCode()
    {
        HashCodeBuilder builder = new HashCodeBuilder(373, 75437)
                .append(metadata)
                .append(deletionInfo());
        for (Cell cell : this)
            builder.append(cell);
        return builder.toHashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || !(o instanceof ColumnFamily))
            return false;

        ColumnFamily comparison = (ColumnFamily) o;

        return metadata.equals(comparison.metadata)
               && deletionInfo().equals(comparison.deletionInfo())
               && ByteBufferUtil.compareUnsigned(digest(this), digest(comparison)) == 0;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ColumnFamily(");
        sb.append(metadata == null ? "<anonymous>" : metadata.cfName);

        if (isMarkedForDelete())
            sb.append(" -").append(deletionInfo()).append("-");

        sb.append(" [").append(CellNames.getColumnsString(getComparator(), this)).append("])");
        return sb.toString();
    }

    public static ByteBuffer digest(ColumnFamily cf)
    {
        MessageDigest digest = FBUtilities.threadLocalMD5Digest();
        if (cf != null)
            cf.updateDigest(digest);
        return ByteBuffer.wrap(digest.digest());
    }

    public void updateDigest(MessageDigest digest)
    {
        for (Cell cell : this)
            cell.updateDigest(digest);
    }

    public static ColumnFamily diff(ColumnFamily cf1, ColumnFamily cf2)
    {
        if (cf1 == null)
            return cf2;
        return cf1.diff(cf2);
    }

    public void resolve(ColumnFamily cf)
    {
        resolve(cf, HeapAllocator.instance);
    }

    public void resolve(ColumnFamily cf, AbstractAllocator allocator)
    {
        // Row _does_ allow null CF objects :(  seems a necessary evil for efficiency
        if (cf == null)
            return;
        addAll(cf, allocator);
    }

    public ColumnStats getColumnStats()
    {
        long minTimestampSeen = deletionInfo().isLive() ? Long.MAX_VALUE : deletionInfo().minTimestamp();
        long maxTimestampSeen = deletionInfo().maxTimestamp();
        StreamingHistogram tombstones = new StreamingHistogram(SSTable.TOMBSTONE_HISTOGRAM_BIN_SIZE);
        int maxLocalDeletionTime = Integer.MIN_VALUE;
        List<ByteBuffer> minColumnNamesSeen = Collections.emptyList();
        List<ByteBuffer> maxColumnNamesSeen = Collections.emptyList();
        for (Cell cell : this)
        {
            minTimestampSeen = Math.min(minTimestampSeen, cell.minTimestamp());
            maxTimestampSeen = Math.max(maxTimestampSeen, cell.maxTimestamp());
            maxLocalDeletionTime = Math.max(maxLocalDeletionTime, cell.getLocalDeletionTime());
            int deletionTime = cell.getLocalDeletionTime();
            if (deletionTime < Integer.MAX_VALUE)
                tombstones.update(deletionTime);
            minColumnNamesSeen = ColumnNameHelper.minComponents(minColumnNamesSeen, cell.name, metadata.comparator);
            maxColumnNamesSeen = ColumnNameHelper.maxComponents(maxColumnNamesSeen, cell.name, metadata.comparator);
        }
        return new ColumnStats(getColumnCount(), minTimestampSeen, maxTimestampSeen, maxLocalDeletionTime, tombstones, minColumnNamesSeen, maxColumnNamesSeen);
    }

    public boolean isMarkedForDelete()
    {
        return !deletionInfo().isLive();
    }

    /**
     * @return the comparator whose sorting order the contained columns conform to
     */
    public CellNameType getComparator()
    {
        return metadata.comparator;
    }

    public boolean hasOnlyTombstones(long now)
    {
        for (Cell cell : this)
            if (cell.isLive(now))
                return false;
        return true;
    }

    public Iterator<Cell> iterator()
    {
        return getSortedColumns().iterator();
    }

    public Iterator<Cell> reverseIterator()
    {
        return getReverseSortedColumns().iterator();
    }

    public boolean hasIrrelevantData(int gcBefore)
    {
        // Do we have gcable deletion infos?
        if (deletionInfo().hasPurgeableTombstones(gcBefore))
            return true;

        // Do we have colums that are either deleted by the container or gcable tombstone?
        DeletionInfo.InOrderTester tester = inOrderDeletionTester();
        for (Cell cell : this)
            if (tester.isDeleted(cell) || cell.hasIrrelevantData(gcBefore))
                return true;

        return false;
    }

    public Map<CellName, ByteBuffer> asMap()
    {
        ImmutableMap.Builder<CellName, ByteBuffer> builder = ImmutableMap.builder();
        for (Cell cell : this)
            builder.put(cell.name, cell.value);
        return builder.build();
    }

    // Note: the returned ColumnFamily will be an UnsortedColumns.
    public static ColumnFamily fromBytes(ByteBuffer bytes)
    {
        if (bytes == null)
            return null;

        try
        {
            return serializer.deserialize(new DataInputStream(ByteBufferUtil.inputStream(bytes)), UnsortedColumns.factory, ColumnSerializer.Flag.LOCAL, MessagingService.current_version);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public ByteBuffer toBytes()
    {
        DataOutputBuffer out = new DataOutputBuffer();
        serializer.serialize(this, out, MessagingService.current_version);
        return ByteBuffer.wrap(out.getData(), 0, out.getLength());
    }

    public abstract static class Factory <T extends ColumnFamily>
    {
        /**
         * Returns a (initially empty) column map whose columns are sorted
         * according to the provided comparator.
         * The {@code insertReversed} flag is an hint on how we expect insertion to be perfomed,
         * either in sorted or reverse sorted order. This is used by ArrayBackedSortedColumns to
         * allow optimizing for both forward and reversed slices. This does not matter for ThreadSafeSortedColumns.
         * Note that this is only an hint on how we expect to do insertion, this does not change the map sorting.
         */
        public abstract T create(CFMetaData metadata, boolean insertReversed);

        public T create(CFMetaData metadata)
        {
            return create(metadata, false);
        }

        public T create(String keyspace, String cfName)
        {
            return create(Schema.instance.getCFMetaData(keyspace, cfName));
        }
    }

}
