package edu.unc.bioinf.ubu.sam;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import net.sf.samtools.SAMRecord;

/**
 * Reader class for SAM or BAM file containing paired reads.
 * Provides the ability to iterate over all mated pairs in the file.
 * Supports multiple mappings for the same read. (i.e. repeating read id's)
 * SAM versus BAM format is determined from the file suffix.  i.e. ".sam" or ".bam"
 * 
 * @author lmose
 */
public class SamReadPairReader implements Iterable<ReadPair> {
    
    private SAMRecord cachedRead;
    private SAMRecord lastRead;
    private Iterator<SAMRecord> iter;
    private int lineCnt = 0;
    private int readPairCacheIdx = Integer.MAX_VALUE;
    private List<ReadPair> readPairCache = new ArrayList<ReadPair>();
    private SAMFileReader inputSam;
    
    public SamReadPairReader(String filename) {
        File inputFile = new File(filename);
        
        inputSam = new SAMFileReader(inputFile);
        inputSam.setValidationStringency(ValidationStringency.SILENT);
  
        iter = inputSam.iterator();
    }
    
    public void close() {
        inputSam.close();
    }
    
    public SAMFileHeader getHeader() {
        return inputSam.getFileHeader();
    }
    
    private List<ReadPair> pairReads(List<SAMRecord> reads1, List<SAMRecord> reads2) {
    	// This Set is used to guarantee uniqueness of ReadPairs
    	Set<ReadPair> readPairSet = new HashSet<ReadPair>();
    	
    	// This List is used to maintain deterministic order (helpful for unit tests)
    	List<ReadPair> readPairs = new ArrayList<ReadPair>();
    	
    	// Pair reads in both directions to include asymmetrically mated pairs
    	pairReads(reads1, reads2, readPairSet, readPairs);
    	pairReads(reads2, reads1, readPairSet, readPairs);
    	
    	return readPairs;
    }

    private void pairReads(List<SAMRecord> reads1, List<SAMRecord> reads2, Set<ReadPair> readPairSet, List<ReadPair> readPairs) {
                
        // Map of mate info to read1
        Map<String, List<SAMRecord>> mate1Map = new HashMap<String, List<SAMRecord>>();
        
        for (SAMRecord read : reads1) {
            String key = read.getMateReferenceName() + "_" + read.getMateAlignmentStart();
            List<SAMRecord> readList = mate1Map.get(key);
            
            if (readList == null) {
            	readList = new ArrayList<SAMRecord>();
            	mate1Map.put(key, readList);
            }
            
            readList.add(read);
        }
        
        for (SAMRecord read2 : reads2) {
        	List<SAMRecord> readList = mate1Map.get(read2.getReferenceName() + "_" + read2.getAlignmentStart());
        	
        	if (readList != null) {
        	
	            for (SAMRecord read1 : readList) {
		            
	                if (read1.getAlignmentStart() > read2.getAlignmentStart()) {
	                    SAMRecord temp = read1;
	                    read1 = read2;
	                    read2 = temp;
	                }
	            
	                ReadPair pair = new ReadPair(read1, read2); 
	                if (!readPairSet.contains(pair)) {
	                	readPairs.add(pair);
	                	readPairSet.add(pair);
	                }
	            }
        	}
        }
    }
    
    private String getBaseName(SAMRecord read) {
        return read.getReadName().substring(0, read.getReadName().length()-2);
    }
    
    private ReadPair getNextReadPair() {
        
        // If read Pairs have already been cached, return them
        if (readPairCacheIdx < readPairCache.size()) {
            return readPairCache.get(readPairCacheIdx++);
        } else {
            
            while (this.hasMoreReads()) {
                readPairCache = getReadPairs();
                readPairCacheIdx = 0;
                
                if (readPairCache.size() > 0) {
                    return readPairCache.get(readPairCacheIdx++);
                }
            }
        }
        
        return null;
    }
    
    private boolean isCacheEmpty() {
        return readPairCacheIdx >= readPairCache.size();
    }
    
    private List<ReadPair> getReadPairs() {
        
        List<SAMRecord> reads1 = new ArrayList<SAMRecord>();
        List<SAMRecord> reads2 = new ArrayList<SAMRecord>();
        
        // Get the list of records for the first read
        SAMRecord read = getNextRead();
        if (read != null) {
            String baseName = getBaseName(read);
            
            while ((read != null) && (baseName.equals(getBaseName(read)))) {
                if ((read.getReadPairedFlag()) && (read.getFirstOfPairFlag())) {
                    reads1.add(read);
                } else if ((read.getReadPairedFlag()) && (read.getSecondOfPairFlag())) {
                    reads2.add(read);
                } else {
//                    System.out.println("Unpaired read: " + read);
                }
                
                read = getNextRead();
            }
                        
            // Put the last read (which isn't part of this sequence) back
            if (read != null) {
                unGetRead();
            }
        }
        
        return pairReads(reads1, reads2);
    }
    
    private boolean hasMoreReads() {
        return cachedRead != null || iter.hasNext();
    }
    
    private SAMRecord getNextRead() {
        SAMRecord next = null;
        
        if (cachedRead != null) {
            next = cachedRead;
            cachedRead = null;
        } else {
            if (iter.hasNext()) {
                next = iter.next();
                lineCnt++;
                if ((lineCnt % 1000000) == 0) {
                    System.out.println("record: " + lineCnt);
                }
            } else {
                next = null;
            }
        }
        
        lastRead = next;
        
        return next;
    }
    
    private void unGetRead() {
        cachedRead = lastRead;
    }

    public Iterator<ReadPair> iterator() {
        return new ReadPairIterator(this);
    }
    
    private static class ReadPairIterator implements Iterator<ReadPair> {

        private SamReadPairReader reader;
        private ReadPair nextReadPair = null;
        
        ReadPairIterator(SamReadPairReader reader) {
            this.reader = reader;
        }
        
        @Override
        public boolean hasNext() {
            if ((nextReadPair == null) && (hasMoreReads())) {
                nextReadPair = reader.getNextReadPair();
            }
            
            return nextReadPair != null;
        }
        
        private boolean hasMoreReads() {
            return !reader.isCacheEmpty() || reader.hasMoreReads();
        }

        @Override
        public ReadPair next() {
            ReadPair pair = nextReadPair;
            nextReadPair = null;
            return pair;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove not supported for ReadPairIterator.");
        }
    }
    
    public static void main(String[] args) {
        SamReadPairReader reader = new SamReadPairReader("/home/lisle/data/coord_convert/sorted_tiny.sam");
        
        for (ReadPair pair : reader) {
            System.out.println(pair);
        }
    }
}
