/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.data.store.cache;

/**
 * A cache entry for an object.  This class is part of the implementation of
 * {@link CachingDataStore}.
 */
final class ObjectCacheEntry extends BasicCacheEntry<Long, byte[]> {

    /**
     * Creates an object cache entry.
     *
     * @param	oid the object ID
     * @param	contextId the context ID associated with the transaction on
     *		whose behalf the entry was created
     * @param	state the state
     */
    private ObjectCacheEntry(long oid, long contextId, State state) {
	super(oid, contextId, state);
    }

    /**
     * Creates a cache entry for an created object newly created by a
     * transaction.
     *
     * @param	oid the object ID
     * @param	contextId the context ID associated with the transaction
     * @return	the cache entry
     */
    static ObjectCacheEntry createNew(long oid, long contextId) {
	return new ObjectCacheEntry(oid, contextId, State.CACHED_WRITE);
    }

    /**
     * Creates a cache entry for an object that is being fetched from the
     * server on behalf of a transaction.
     *
     * @param	oid the object ID
     * @param	contextId the context ID associated with the transaction
     * @param	forUpdate whether the object is being fetched for update
     * @return	the cache entry
     */
    static ObjectCacheEntry createFetching(
	long oid, long contextId, boolean forUpdate)
    {
	return new ObjectCacheEntry(
	    oid, contextId,
	    forUpdate ? State.FETCHING_WRITE : State.FETCHING_READ);
    }

    /**
     * Creates a cache entry for an object that has been fetched for read from
     * the server on behalf of a transaction.
     *
     * @param	oid the object ID
     * @param	contextId the context ID associated with the transaction
     * @param	data the object data
     * @return	the cache entry     
     */
    static ObjectCacheEntry createCached(
	long oid, long contextId, byte[] data)
    {
	ObjectCacheEntry entry =
	    new ObjectCacheEntry(oid, contextId, State.CACHED_READ);
	entry.setValue(data);
	return entry;
    }

    @Override
    public String toString() {
	byte[] data = getValue();
	return "ObjectCacheEntry[" +
	    "oid:" + key +
	    ", data:" + (data == null ? "null" : "byte[" + data.length + "]") +
	    ", contextId:" + getContextId() +
	    ", state:" + getState() +
	    "]";
    }

    /**
     * Returns the hash code for this entry's key.
     *
     * @return	the hash code for the key
     */
    int keyHashCode() {
	return keyHashCode(key);
    }

    /**
     * Returns the hash code for an object ID.
     *
     * @param	oid the object ID
     * @return	the hash code for the object ID
     */
    static int keyHashCode(long oid) {
	return (int) (oid ^ (oid >>> 32));
    }
}
