package org.directcache.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.directcache.ICacheEntry;
import org.directcache.IDirectCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectCache2 implements IDirectCache {

	// suggested java memory settings:
	//-Xms512m -Xmx512m -XX:MaxPermSize=512m -Xss512k
	//   - win32 with 4gb - allocated 1250mb
	//   - win32 with 2gb - allocated 490mb

	// have to try this
	//-XX:MaxDirectMemorySize
	//java -d64 -XX:MaxDirectMemorySize=12g -XX:+UseLargePages com.example.MyApp	
	// see http://www.kdgregory.com/index.php?page=java.byteBuffer
	// and http://www.kdgregory.com/programming/java/ByteBuffer_JUG_Presentation.pdf
	
	private static Logger logger=LoggerFactory.getLogger(DirectCache2.class);
	
	private Map<String, ICacheEntry> entries;

	private AtomicLong capacity;
//	private AtomicLong usedMemory;
	private int defaultDuration=-1;
	
	private void setup(long capacity) {
		this.capacity = new AtomicLong(capacity);
		// these params make things considerably worse
//		entries = new ConcurrentHashMap <String, ICacheEntry>(60000, 0.75F, 30);
		entries = new ConcurrentHashMap <String, ICacheEntry>();
		logger.info("DirectCache allocated with " + capacity + " bytes buffer");
//		usedMemory = new AtomicLong(0);
	}
		
	public DirectCache2() {
		// defaults to 50mb
		setup(50*1024*1024);
	}
	public DirectCache2(int capacity) {
		setup(capacity);
	}
	
	public void reset() {
		setup(this.capacity.get());
	}

	public int getDefaultDuration() {
		return defaultDuration;
	}
	public void setDefaultDuration(int defaultDuration) {
		this.defaultDuration = defaultDuration;
	}
	public Map<String, ICacheEntry> entries() {
		return this.entries;
	}

	private byte[] serializeObject(Serializable obj) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(obj);
		oos.close();
		byte[] b = baos.toByteArray();
		logger.debug("object serialized");
		return b;		
	}

	public ICacheEntry storeObject(String key, Serializable obj) {
		return storeObject(key, obj, defaultDuration);
	}
	
	public ICacheEntry storeObject(String key, Serializable obj, int duration) {

		//logger.info("attempting to remove object with key '" + key + "' - just in case");

		//removeObject(key);
		
		logger.info("serializing object with key '" + key + "'");

		byte source[] = null;

		try {
			source = serializeObject(obj);
			logger.info("object with key '" + key + "' serialized (" + source.length + ") bytes");
		} catch (IOException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
			logger.error("error serializing object with key '" + key + "': " + e2.getMessage());
		}
		
//  lasciamo stare fino a che non gestiamo la capacity
//		if (usedMemory.get() >= capacity.get()) {
//			ICacheEntry entryToRemove = expiredEntryLargerThan(source.length);
//			if (entryToRemove == null) {
//				logger.info("collecting LRU item - no room for entry with key '" + key + "'");
//				entryToRemove = collectLRU(source.length);
//			}
//		}
		
		CacheEntry2 newEntry = null;
		
		while  (newEntry == null) {
			logger.debug("trying to create entry for object with key '" + key + "'");
			newEntry = CacheEntry2.allocate(key, source, duration);
			if (newEntry == null) {
				logger.debug("removing one expired entry - no room for entry with key '" + key + "'");
				ICacheEntry entryToRemove = expiredEntryLargerThan(source.length);
				if (entryToRemove != null) {
					removeObject(entryToRemove.getKey());
				} else {
					logger.debug("collecting LRU item - no room for entry with key '" + key + "'");
					collectLRU(source.length);
				}
			}
		}

		//		usedMemory+=storedEntry.size();
//		usedMemory.addAndGet(storedEntry.size());

		entries.put(key, newEntry);

		logger.debug("stored entry with key '" + key + "'");

		return newEntry;
	}
	
	private ICacheEntry expiredEntryLargerThan(int size) {
	
//		synchronized (entries) {
			for (ICacheEntry cacheEntry : entries.values()) {
				if (cacheEntry.size() >= size && cacheEntry.expired()) {
					logger.debug("expired entry found for size " + size);
					return cacheEntry;
				}
			}
//		}
		
		logger.debug("No expired entry found for size " + size);
		return null;
	}

	public void collectLRU(int bytesToFree) {	

		logger.debug("Attempting LRU collection for " + bytesToFree + " bytes");

		long freedBytes = 0;
		for (ICacheEntry entry : entries.values()) {
			freedBytes += entry.getSize();
			removeObject(entry.getKey());
			logger.debug("Collected LRU entry " + entry.getKey());
			if (freedBytes >= bytesToFree)
				return;
		}
		
		logger.debug("No LRU entries to collect for " + bytesToFree + " bytes");
	}
	
	public Serializable retrieveObject(String key)  {

		logger.info("looking for object with key '" + key + "'");
		
		CacheEntry2 entry = (CacheEntry2)entries.get(key);

		if (entry == null) {
			logger.info("could not find object with key '" + key + "'");
			return null;
		}
		
		byte[] dest = entry.getBuffer();
		
		if (dest == null) { 
			logger.error("invalid buffer");
			return null;
		}
		
		try {
			Serializable obj = deserialize(dest);
			logger.info("retrieved object with key '" + key + "' (" + 
					dest.length + " bytes)");
			entry.touch();
			return obj;
		} catch (EOFException ex) {
			logger.error("EOFException deserializing object with key '"
					+ key + "' with size " + entry.size());
			return null;
		} catch (IOException e) {
			logger.error("IOException deserializing object with key '"
					+ key + "' with size " + entry.size());
		} catch (ClassNotFoundException e) {
			logger.error("ClassNotFoundException deserializing object with key '"
					+ key + "' with size " + entry.size());
		}
		return null;
	}
	
	private Serializable deserialize(byte[] b) throws IOException, ClassNotFoundException {
		ByteArrayInputStream bis = new ByteArrayInputStream(b);
		ObjectInputStream ois = new ObjectInputStream(bis);
		Serializable obj = (Serializable) ois.readObject();
		ois.close();
		return obj;
	}
	
	public void collectExpired() {	
		
		logger.debug("Looking for expired entries");

//		List<CacheEntry> expiredList = filter(
//										having(on(CacheEntry.class).expired())
//										, entries.values()
//									);

		List<ICacheEntry> expiredList = new Vector<ICacheEntry>();
		
//		synchronized (entries) {
			for (ICacheEntry cacheEntry : entries.values()) {
				if (cacheEntry.expired())
					expiredList.add(cacheEntry);
			}
//		}
		logger.debug("Collecting " + expiredList.size() +  " expired entries");
		
		for (ICacheEntry expired : expiredList) {
			removeObject(expired.getKey());
		}

		logger.debug("Collected " + expiredList.size() +  " expired entries");		
	}	
	
	public ICacheEntry removeObject(String key) {

		logger.info("trying to remove entry with key '" + key + "'");	

		ICacheEntry entry = null;
		
//		synchronized (entries) {
			entry = entries.remove(key);
//		}
		
		if (entry != null) {
//			usedMemory.addAndGet(-entry.size());
//			usedMemory-=entry.size();
			entry.dispose();
			logger.info("object with key '" + key + "' disposed");
		}

		return entry;
	}
	
	public long remaining() {
//		return capacity.get()-usedMemory.get();
		return 0;
	}
	public long usedMemory() {
//		return usedMemory.get();
		long totalSize = 0;
		for (ICacheEntry entry:entries.values()) {
			totalSize += entry.size();
		}
		
		return totalSize;
	}
	
	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		
		sb.append("DirectCache {" );
		sb.append("entries: ");
		sb.append(entries().size());
		sb.append(", ");
//		sb.append("capacity (mb): ");
//		sb.append(capacity()/1024/1024);
//		sb.append(", ");
		sb.append("size (mb): ");
		sb.append(usedMemory()/1024/1024);
		sb.append(", ");
		sb.append("remaining (mb): ");
		sb.append(remaining()/1024/1024);
		sb.append("}");
		
		return sb.toString();
	}

	@Override
	public long capacity() {
		// deve ritornare -XX:MaxDirectMemorySize 
		return 512*1024*1024L;
	}
	
}