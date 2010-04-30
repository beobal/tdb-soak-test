package com.talis;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.tdb.TDB;
import com.hp.hpl.jena.tdb.TDBFactory;
import com.hp.hpl.jena.tdb.sys.SystemTDB;


public class UpdateSoakTest{
	
	static Logger LOG = LoggerFactory.getLogger(UpdateSoakTest.class);

	private Dataset myDataset;
	private Lock myLock;
	
	private static boolean KEEP_RUNNING = true;
	
	public UpdateSoakTest(String location){
		myDataset = TDBFactory.createDataset(location);
		myLock = new ReentrantLock(true);
	}
    
    public void doUpdate(List<Statement> statements) throws Exception {
    	myLock.lock();
    	try{
    		Model model = getDefaultModel();
    		addStatements(statements, model);
    		syncChangesToDisk();
    	}finally{
    		myLock.unlock();
    	}
    }
    
    private void syncChangesToDisk() throws Exception {
		
		Object fileMode = TDB.getContext().get(SystemTDB.symFileMode);
		if ( null == fileMode || ! fileMode.toString().equals("mapped") ) {
			
			if (LOG.isDebugEnabled()){
				LOG.debug("About to sync TDB dataset...");
			}
			
			TDB.sync( getDefaultModel() );

			if (LOG.isDebugEnabled()){
				LOG.debug("Synched TDB dataset");
			}
		} else {
			if (LOG.isDebugEnabled()){
				LOG.debug("File mode is not mapped - not synching TDB to disk");
			}
		}
	}
    
    public Model getDefaultModel(){
    	return myDataset.getDefaultModel();
    }
    
    private void addStatements(List<Statement> statements, Model model) 
    throws Exception {
		
		if (LOG.isDebugEnabled()){
		
		}
		model.add(statements);
		if (LOG.isDebugEnabled()){

		}
	}
    
    public void runTest(int numWorkers){
    	ScheduledExecutorService memoryReporter = 
    		Executors.newSingleThreadScheduledExecutor();
    	memoryReporter.scheduleAtFixedRate(
    			new MemoryUsageLogger(), 5, 5, TimeUnit.SECONDS);
    	
    	for (int i = 0; i < numWorkers; i++) {
    		UpdateSoakTestWorker worker = new UpdateSoakTestWorker(i); 
    		Thread t = new Thread(worker);
    		t.start();
    	}
    	
    	File flag = new File("/tmp/stop");
    	while (KEEP_RUNNING){
    		if (flag.exists()){
    			KEEP_RUNNING = false;
    			LOG.info("Stopping Test");
    			memoryReporter.shutdownNow();
    		}
    	}
    }
    
    public static void main( String[] args ) throws InterruptedException {
    	String location = args[0];
    	int numWorkers = Integer.parseInt(args[1]);
    	LOG.info("Starting test with " + numWorkers + " in location " + location);
    	UpdateSoakTest test = new UpdateSoakTest(location);
    	test.runTest(numWorkers);
    }

    class UpdateSoakTestWorker implements Runnable {
    	int counter = 0;
    	int id;
    	
    	public UpdateSoakTestWorker(int id) {
			this.id = id;
		}
    	
		@Override
		public void run() {
			while (KEEP_RUNNING){
				List<Statement> statements = new ArrayList<Statement>();
				
				Resource subject = ResourceFactory.createResource(
							"urn:uuid:" + UUID.randomUUID().toString());
				
				String uuid = UUID.randomUUID().toString();
				for (int i = 0; i < 5; i++) {
					Property predicate = ResourceFactory.createProperty(
							"urn:uuid:" + i + ":" + uuid);
					statements.add( 
						ResourceFactory.createStatement(subject, predicate, 
								ResourceFactory.createPlainLiteral("uuid:0:" + i)));
					statements.add( 
						ResourceFactory.createStatement(subject, predicate, 
								ResourceFactory.createPlainLiteral("uuid:1:" + i)));
					
					statements.add( 
						ResourceFactory.createStatement(subject, predicate, 
								ResourceFactory.createResource("urn:uuid:" + uuid + ":0")));
					statements.add( 
						ResourceFactory.createStatement(subject, predicate, 
								ResourceFactory.createResource("urn:uuid:" + uuid + ":1")));
				}
				try {
					doUpdate(statements);
					counter = counter + statements.size();
					LOG.info("Worker " + id + " total statements added : " + counter);
				} catch (Exception e) {
					LOG.warn("Worker caught exception when applying update", e);
				}
			}
		}
    }
    
    class MemoryUsageLogger implements Runnable{

		public static final long DEFAULT_MEMORY_USAGE_LOGGING_INTERVAL = 60000l;
		public static final String MEMORY_USAGE_LOGGING_INTERVAL_PROPERTY = 
			"com.talis.platform.memoryUsageLoggingInterval";
		public static final String MEMORY_MESSAGE_TEMPLATE = 
			"Memory Usage (committed/used) | Heap: %s/%s | Non-Heap %s/%s ";
		public static final String CPU_MESSAGE_TEMPLATE = 
			"CPU Usage (committed/used) | Heap: %s/%s | Non-Heap %s/%s ";
		public static final String GC_MESSAGE_TEMPLATE = 
			"GC Data (type:collections/duration) | %s:%s/%s";
	
		Map<String, Long> gcDurations = new HashMap<String, Long>();
		Map<String, Long> gcCounts = new HashMap<String, Long>();
		
		@Override
		public void run() {
			MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
			MemoryUsage heap = memBean.getHeapMemoryUsage();
			MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
	
			String message = String.format(MEMORY_MESSAGE_TEMPLATE, FileUtils
					.byteCountToDisplaySize(heap.getCommitted()), FileUtils
					.byteCountToDisplaySize(heap.getUsed()), FileUtils
					.byteCountToDisplaySize(nonHeap.getCommitted()), FileUtils
					.byteCountToDisplaySize(nonHeap.getUsed()), FileUtils
					.byteCountToDisplaySize(nonHeap.getMax()));
			if (LOG.isInfoEnabled()) {
				LOG.info(message);
			}
			
			for (GarbageCollectorMXBean gcBean :
					ManagementFactory.getGarbageCollectorMXBeans()){
				Long duration = gcBean.getCollectionTime();
				Long lastDuration = gcDurations.get(gcBean.getName());
				Long timeSpentThisPeriod;
				if (null != lastDuration){
					timeSpentThisPeriod = (duration - lastDuration);
				}else{
					timeSpentThisPeriod = duration;
				}
				gcDurations.put(gcBean.getName(), duration);
				
				Long count = gcBean.getCollectionCount();
				Long lastCount = gcCounts.get(gcBean.getName());
				Long collectionsThisPeriod;
				if (null != lastCount){
					collectionsThisPeriod = (count - lastCount);
				}else{
					collectionsThisPeriod = count;
				}
				gcCounts.put(gcBean.getName(), count);
				LOG.info(String.format(GC_MESSAGE_TEMPLATE, 
										gcBean.getName(), 
										collectionsThisPeriod, 
										timeSpentThisPeriod));
			}
			
		}
    }
    
}
