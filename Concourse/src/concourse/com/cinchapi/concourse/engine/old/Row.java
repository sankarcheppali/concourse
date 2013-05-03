/*
 * This project is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This project is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this project. If not, see <http://www.gnu.org/licenses/>.
 */
package com.cinchapi.concourse.engine.old;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;

import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinchapi.common.cache.ObjectReuseCache;
import com.cinchapi.common.io.IterableByteSequences;
import com.cinchapi.common.util.Hash;
import com.cinchapi.common.util.Strings;
import com.cinchapi.concourse.db.Cell2;
import com.cinchapi.concourse.db.Key;
import com.cinchapi.concourse.exception.ConcourseRuntimeException;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * <p>
 * A thread-safe<sup>1</sup> {@link Cell2} collection that makes it possible to
 * perform <em>request</em> reads.
 * </p>
 * <p>
 * Each row is stored in its own distinct file on disk<sup>2</sup>. The entire
 * row is deserialized or loaded from a cache whenever a read or write involving
 * the column is occurs. To afford the caller flexibility, changes to a row are
 * not automatically flushed to disk, but must be explicitly done by calling
 * {@link #fsync()}.
 * </p>
 * <p>
 * In memory, each row maintains an index mapping column names to cells. A row
 * can hold up to {@value #MAX_NUM_CELLS} cells throughout its lifetime, but in
 * actuality this limit is lower because the size of a cell can very widely and
 * is guaranteed to increase with every revision. Therefore it is more useful to
 * use the maximum allowable storage as a guide.
 * </p>
 * <p>
 * The size of a row is the sum of the sizes for each of its cells. A row cannot
 * occupy more than {@value #MAX_SIZE_IN_BYTES} bytes.
 * </p>
 * <p>
 * <sup>1</sup> - Each row uses a locking protocol that allows multiple
 * concurrent readers, but only one concurrent writer.<br>
 * <sup>2</sup> - Rows are hashed into storage buckets (directories) based on
 * the identifying {@link Key}.
 * </p>
 * 
 * @author jnelson
 */
final class Row extends DurableIndex<Key, String, Cell2> {
	// NOTE: This class does not define hashCode() or equals() because the
	// defaults are the desired behaviour.

	/**
	 * Return the {@code Row} identified by {@code key}.
	 * 
	 * @param key
	 * @param home
	 *            - the home directory where rows are stored
	 * @return the row
	 */
	static Row identifiedBy(Key key, String home) {
		Row row = cache.get(key);
		if(row == null) {
			String filename = home + File.separator
					+ Utilities.getStorageFileNameFor(key);
			try {
				File file = new File(filename);
				file.getParentFile().mkdirs();
				file.createNewFile();

				byte[] bytes = new byte[(int) file.length()];
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				new FileInputStream(filename).getChannel().read(buffer);
				buffer.rewind();
				row = fromByteSequences(filename, key, buffer);
				cache.put(row, key);
			}
			catch (IOException e) {
				log.error(
						"An error occured while trying to deserialize row {} from {}: {}",
						key, filename, e);
				throw new ConcourseRuntimeException(e);
			}
		}
		return row;
	}

	/**
	 * Return the row represented by {@code bytes}. Use this method when
	 * reading and reconstructing from a file. This method assumes that
	 * {@code bytes} was generated using {@link #getBytes()}.
	 * 
	 * @param filename
	 * @param key
	 * @param bytes
	 * @return the row
	 */
	private static Row fromByteSequences(String filename, Key key,
			ByteBuffer bytes) {
		HashMap<String, Cell2> cells = Maps.newHashMapWithExpectedSize(bytes
				.capacity() / Cell2.WEIGHTED_SIZE_IN_BYTES); // this will likely
															// take up more
															// memory than
															// necessary
		int nonEmptyCells = 0;
		byte[] array = new byte[bytes.remaining()];
		bytes.get(array);
		IterableByteSequences.ByteSequencesIterator bsit = IterableByteSequences.ByteSequencesIterator
				.over(array);
		while (bsit.hasNext()) {
			Cell2 cell = Cell2.fromByteSequence(bsit.next());
			cells.put(cell.getColumn(), cell);
			nonEmptyCells += cell.isEmpty() ? 0 : 1;
		}
		return new Row(filename, key, cells, nonEmptyCells);
	}

	/**
	 * A larger name length allows more buckets (and therefore a smaller
	 * bucket:row ratio), however the filesystem can act funny if a single
	 * directory has too many files. This number should seek to have the
	 * bucket:row ratio that is similar to the number of possible buckets while
	 * being mindful of not having too many files in a single directory.
	 */
	private static final int STORAGE_BUCKET_NAME_LENGTH = 4;
	private static final String STORAGE_FILE_NAME_EXTENSION = ".cr";
	private static final ObjectReuseCache<Row> cache = new ObjectReuseCache<Row>();

	/**
	 * The maximum allowable size of a row.
	 */
	public static final int MAX_SIZE_IN_BYTES = Integer.MAX_VALUE;

	/**
	 * The weighted maximum number of cells that can exist in a row.
	 * In actuality, this limit is much lower because the size of a cell can
	 * very widely.
	 */
	public static final int MAX_NUM_CELLS = MAX_SIZE_IN_BYTES
			/ Cell2.WEIGHTED_SIZE_IN_BYTES;
	private static final Logger log = LoggerFactory.getLogger(Row.class);

	private transient int nonEmptyCells;

	/**
	 * Construct a new instance.
	 * 
	 * @param key
	 * @param cells
	 * @param nonEmptyCells
	 */
	private Row(String filename, Key key, HashMap<String, Cell2> cells,
			int nonEmptyCells) {
		super(filename, key, cells);
		this.nonEmptyCells = nonEmptyCells;
	}

	/**
	 * Return the size of the cell under {@code column}.
	 * 
	 * @param column
	 * @return the size
	 */
	public int size(String column) {
		lock.readLock().lock();
		try {
			if(components.containsKey(column)) {
				return components.get(column).size();
			}
			return 0;
		}
		finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public String toString() {
		return Strings.toString(this);
	}

	@Override
	protected Logger getLogger() {
		return log;
	}

	/**
	 * Add {@code column} as {@code value}.
	 * 
	 * @param column
	 * @param value
	 */
	void add(String column, Value value) {
		lock.writeLock().lock();
		try {
			Cell2 cell;
			if(components.containsKey(column)) {
				cell = components.get(column);
			}
			else {
				cell = Cell2.newInstance(column);
				components.put(column, cell);
				nonEmptyCells++;
			}
			cell.add(value);
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * Return the columns that have non-empty cells.
	 * 
	 * @return the set of columns with non empty cells in the row
	 */
	Set<String> describe() { // O(n)
		lock.readLock().lock();
		try {
			Set<String> columns = Sets.newHashSetWithExpectedSize(components
					.size());
			Iterator<Cell2> it = components.values().iterator();
			while (it.hasNext()) {
				Cell2 cell = it.next();
				if(!cell.getValues().isEmpty()) {
					columns.add(cell.getColumn());
				}
			}
			return columns;
		}
		finally {
			lock.readLock().unlock();
		}

	}

	/**
	 * Return the columns that had non-empty cell {@code at} the specified
	 * timestamp.
	 * 
	 * @param at
	 * @return the columns.
	 */
	Set<String> describe(long at) {
		lock.readLock().lock();
		try {
			Set<String> columns = Sets.newHashSetWithExpectedSize(components
					.size());
			Iterator<Cell2> it = components.values().iterator();
			while (it.hasNext()) {
				Cell2 cell = it.next();
				if(!cell.getValues(at).isEmpty()) {
					columns.add(cell.getColumn());
				}
			}
			return columns;
		}
		finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Return {@code true} if {@code value} exists under {@code column}.
	 * 
	 * @param column
	 * @param value
	 * @return {@code true} if {@code value} exists under {@code column}
	 */
	boolean exists(String column, Value value) { //O(1)
		lock.readLock().lock();
		try {
			if(components.containsKey(column)) {
				return components.get(column).contains(value);
			}
			return false;
		}
		finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Fetch the cell under {@code column}, even if the cell is empty.
	 * 
	 * @param column
	 * @return the cell.
	 */
	@Nullable
	Cell2 fetch(String column) { //O(1)
		lock.readLock().lock();
		try {
			return components.get(column);
		}
		finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Return {@code true} if the row is empty.
	 * 
	 * @return {@code true} if there are 0 non-empty cells in the row.
	 */
	boolean isEmpty() {
		return nonEmptyCells == 0;
	}

	/**
	 * Remove {@code} column as {@code value}. This method does not perform any
	 * consistency checks.
	 * 
	 * @param column
	 * @param value
	 */
	void remove(String column, Value value) {
		lock.writeLock().lock();
		try {
			if(components.containsKey(column)) {
				components.get(column).remove(value);
				nonEmptyCells -= components.get(column).isEmpty() ? 1 : 0;
				// NOTE: Even if the cell is now empty, it should stay in the
				// row because it has history
			}
		}
		finally {
			lock.writeLock().unlock();
		}

	}

	/**
	 * Utilities for the {@link Row} class.
	 * 
	 * @author jnelson
	 */
	private static final class Utilities {

		/**
		 * Return the storage filename for the row identified by {@code key}.
		 * 
		 * @param key
		 * @return the storage filename
		 */
		public static String getStorageFileNameFor(Key key) {
			StringBuilder sb = new StringBuilder();
			sb.append(getStorageBucketFor(key));
			sb.append(File.separator);
			sb.append(key.asLong());
			sb.append(STORAGE_FILE_NAME_EXTENSION);
			return sb.toString();
		}

		/**
		 * Return the appropriate storage bucket for the row identified by
		 * {@code key}.
		 * 
		 * @param key
		 * @return the storage bucket
		 */
		private static String getStorageBucketFor(Key key) {
			return Hash.toString(Hash.sha256(key.getBytes())).substring(0,
					STORAGE_BUCKET_NAME_LENGTH);
		}
	}
}
