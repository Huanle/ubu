package edu.unc.bioinf.ubu.assembly;

import static edu.unc.bioinf.ubu.assembly.OperatingSystemCommand.runCommand;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.sf.picard.sam.BuildBamIndex;
import net.sf.picard.sam.SamFormatConverter;
import net.sf.picard.sam.SortSam;
import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import edu.unc.bioinf.ubu.chimera.CombineChimera;
import edu.unc.bioinf.ubu.fastq.Sam2Fastq;
import edu.unc.bioinf.ubu.gtf.Feature;
import edu.unc.bioinf.ubu.gtf.GtfLoader;
import edu.unc.bioinf.ubu.sam.ReadBlock;
import edu.unc.bioinf.ubu.sam.ReverseComplementor;

public class ReAligner {

	private static final int MAX_UNALIGNED_READS = 1000000;
	private static final int MAX_REGION_LENGTH = 2000;
	private static final int MIN_REGION_REMAINDER = 500;
	private static final int REGION_OVERLAP = 200; 
	private static final long RANDOM_SEED = 1;
	private static final int MIN_CHIMERIC_READ_MAPQ = 2;
	private static final int MAX_POTENTIAL_UNALIGNED_CONTIGS = 3000000;
	
	private int missingXATag = 0;
	
	private SAMFileHeader samHeader;

//	private SAMFileWriter outputReadsBam;

	private long startMillis;

	private List<Feature> regions;

	private String regionsGtf;

	private String tempDir;
	
	private String unalignedRegionSam;

	private String reference;
	
	private String referenceDir;
	
	private int minContigMapq;

	private AssemblerSettings assemblerSettings;
	
	private int numThreads;
	
	private int allowedMismatchesFromContig = 0;
	
	private boolean shouldReprocessUnaligned = true;
	
	private List<ReAlignerRunnable> threads = new ArrayList<ReAlignerRunnable>();
	
//	private List<SAMRecord> unalignedReads = new ArrayList<SAMRecord>();
	
	private ReverseComplementor reverseComplementor = new ReverseComplementor();
	
	private boolean useSmallAlignerIndex = false;
	
	public void setUseSmallAlignerIndex(boolean smallAlignerIndex) {
		this.useSmallAlignerIndex = smallAlignerIndex;
	}

	public void reAlign(String inputSam, String outputSam) throws Exception {

		System.out.println("input: " + inputSam);
		System.out.println("output: " + outputSam);
		System.out.println("regions: " + regionsGtf);
		System.out.println("reference: " + reference);
		System.out.println("reference: " + referenceDir);
		System.out.println("working dir: " + tempDir);
		System.out.println("num threads: " + numThreads);
		System.out.println("allowed mismatches: " + allowedMismatchesFromContig);
		System.out.println(assemblerSettings.getDescription());
		
		System.out.println("Java version: " + System.getProperty("java.version"));

		startMillis = System.currentTimeMillis();

		init();

		log("Loading target regions");
		loadRegions();

		log("Reading Input SAM Header");
		getSamHeader(inputSam);

//		log("Initializing output SAM File");
//		initOutputFile(outputSam);
		
		samHeader.setSortOrder(SAMFileHeader.SortOrder.unsorted);
		
		if (shouldReprocessUnaligned) {		
			log("Assembling unaligned reads");
			
			String unalignedSam = tempDir + "/unaligned.bam";
			unalignedSam = getUnalignedReads(inputSam, unalignedSam);
			
//			String unalignedSam = tempDir + "/" + "unaligned_to_contig.bam";
			
			String unalignedDir = tempDir + "/unaligned";
			String unalignedContigFasta = unalignedDir + "/unaligned_contigs.fasta";
			unalignedRegionSam = unalignedDir + "/unaligned_region.bam";
			String sortedUnalignedRegion = unalignedDir + "/sorted_unaligned_region";
			
			Assembler assem = newUnalignedAssembler();
			boolean hasContigs = assem.assembleContigs(unalignedSam, unalignedContigFasta, "unaligned");
			// Make eligible for GC
			assem = null;
			
//			String finalUnaligned = unalignedDir + "/" + "unaligned_to_contig.bam";
			
			if (hasContigs) {
				processContigs(unalignedContigFasta, unalignedDir, unalignedSam, unalignedRegionSam);
//				runCommand("samtools sort " + unalignedRegionSam + " " + sortedUnalignedRegion);
				sortBam(unalignedRegionSam, sortedUnalignedRegion + ".bam", "coordinate");
				unalignedRegionSam = sortedUnalignedRegion + ".bam";
				
//				runCommand("samtools index " + unalignedRegionSam);
				indexBam(unalignedRegionSam);

			} else {
				shouldReprocessUnaligned = false;
			}
		}
		
		log("Iterating over regions");
		for (Feature region : regions) {
//			processRegion(region, inputSam);
			log("Spawning thread for: " + region.getDescriptor());
			spawnRegionThread(region, inputSam);
		}
		
		log("Waiting for all threads to complete");
		waitForAllThreadsToComplete();
		
		log("Combining contigs");
		String contigFasta = tempDir + "/" + "all_contigs.fasta";
		combineContigs(contigFasta);
		
		processContigs(contigFasta, tempDir, inputSam, outputSam);
		
		System.out.println("Multiple best hit reads missing XA tag: " + this.missingXATag);
		
		System.out.println("Done.");
	}
	
	private void processContigs(String contigFasta, String tempDir, String inputSam, String outputSam) throws InterruptedException, IOException {
		
		SAMFileWriter outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				samHeader, true, new File(outputSam));
		
		log("Aligning contigs");
		Aligner aligner = new Aligner(reference, numThreads);
		String contigsSam = tempDir + "/" + "all_contigs.sam";
		aligner.align(contigFasta, contigsSam);
		
		log("Processing chimeric reads");
		CombineChimera cc = new CombineChimera();
		String contigsWithChim = tempDir + "/" + "all_contigs_chim.sam";
		cc.combine(contigsSam, contigsWithChim, MIN_CHIMERIC_READ_MAPQ);
		
		log("Cleaning contigs");
		String cleanContigsFasta = tempDir + "/" + "clean_contigs.fasta";
		boolean hasCleanContigs = cleanAndOutputContigs(contigsWithChim, cleanContigsFasta);
		
		if (hasCleanContigs) {
			log("Aligning original reads to contigs");
			String alignedToContigSam = tempDir + "/" + "align_to_contig.sam";
			this.alignToContigs(inputSam, alignedToContigSam, cleanContigsFasta);
			
			log("Convert aligned to contig to bam, and sorting bams");
			String alignedToContigBam = tempDir + "/" + "align_to_contig.bam";
			String sortedAlignedToContig = tempDir + "/" + "sorted_aligned_to_contig";
			String sortedOriginalReads = tempDir + "/" + "sorted_original_reads"; 
			//runCommand("samtools view -bS " + alignedToContigSam + " -o " + alignedToContigBam);
			samToBam(alignedToContigSam, alignedToContigBam);
			
//			runCommand("samtools sort -n " + alignedToContigBam + " " + sortedAlignedToContig);
//			runCommand("samtools sort -n " + inputSam + " " + sortedOriginalReads);
			sortedAlignedToContig += ".bam";
			sortedOriginalReads += ".bam";
			
			sortBamsByName(alignedToContigBam, inputSam, sortedAlignedToContig, sortedOriginalReads);
			
			log("Adjust reads");
//			String unaligned = tempDir + "/" + "unaligned_to_contig.bam";
			adjustReads(sortedOriginalReads, sortedAlignedToContig, outputReadsBam);
		}
		
		outputReadsBam.close();
	}
	
	private void indexBam(String bam) {
		String[] args = new String[] { 
				"INPUT=" + bam,
				"VALIDATION_STRINGENCY=SILENT"
				};
		
		int ret = new BuildBamIndex().instanceMain(args);
		if (ret != 0) {
			throw new RuntimeException("BuildBamIndex failed");
		}
	}
	
	private void sortBamsByName(String in1, String in2, String out1, String out2) throws InterruptedException {
		if (numThreads > 1) {
			System.out.println("Sorting BAMs in parallel");
			SortBamRunnable runnable1 = new SortBamRunnable(this, in1, out1, "queryname");
			Thread thread1 = new Thread(runnable1);
			
			SortBamRunnable runnable2 = new SortBamRunnable(this, in2, out2, "queryname");
			Thread thread2 = new Thread(runnable2);

			thread1.start();
			thread2.start();
			
			thread1.join();
			thread2.join();
		} else {
			System.out.println("Sorting bams sequentially");
			sortBam(in1, out1, "queryname");
			sortBam(in2, out2, "queryname");
		}
	}
	
	void sortBam(String input, String output, String sortOrder) {
		String[] args = new String[] { 
				"INPUT=" + input, 
				"OUTPUT=" + output, 
				"VALIDATION_STRINGENCY=SILENT",
				"SORT_ORDER=" + sortOrder,
				"TMP_DIR=" + this.tempDir + "/sorttmp"
				};
		
		int ret = new SortSam().instanceMain(args);
		if (ret != 0) {
			throw new RuntimeException("SortSam failed");
		}
	}
	
	//TODO: Sort in place when necessary
	private void samToBam(String sam, String bam) {
		String[] args = new String[] { "INPUT=" + sam, "OUTPUT=" + bam, "VALIDATION_STRINGENCY=SILENT" };
		int ret = new SamFormatConverter().instanceMain(args);
		if (ret != 0) {
			throw new RuntimeException("SamFormatConverter failed.");
		}
	}
	
//	private void concatenateBamsOld(String bam1, String bam2, String outputBam) throws InterruptedException, IOException {
//		runCommand("samtools cat " + bam1 + " " + bam2 + " -o " + outputBam);
//	}
	
	private void concatenateBams(String bam1, String bam2, String outputBam) {
		
		SAMFileWriter outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				samHeader, true, new File(outputBam));
		
		SAMFileReader reader = new SAMFileReader(new File(bam1));
		reader.setValidationStringency(ValidationStringency.SILENT);
		
		for (SAMRecord read : reader) {
			outputReadsBam.addAlignment(read);
		}
		
		reader.close();
		
		reader = new SAMFileReader(new File(bam2));
		reader.setValidationStringency(ValidationStringency.SILENT);
		
		for (SAMRecord read : reader) {
			outputReadsBam.addAlignment(read);
		}
		
		reader.close();
		
		outputReadsBam.close();
	}
	
	private void combineContigs(String contigFasta) throws IOException, InterruptedException {

		System.out.println("Combining contigs...");
		
		BufferedWriter writer = new BufferedWriter(new FileWriter(contigFasta, false));
		
		for (Feature region : regions) {
			String regionContigsFasta = tempDir + "/" + region.getDescriptor() + "_contigs.fasta";
			BufferedReader reader = new BufferedReader(new FileReader(regionContigsFasta));
			String line = reader.readLine();
			while (line != null) {
				writer.write(line);
				writer.write('\n');
				line = reader.readLine();
			}
			reader.close();
		}
		
		writer.close();
	}
	
	/*
	private void combineContigsOld(String contigFasta) throws IOException, InterruptedException {

//		StringBuffer files = new StringBuffer();
//		
//		for (Feature region : regions) {
//			String regionContigsFasta = tempDir + "/" + region.getDescriptor() + "_contigs.fasta";
//			files.append(" " + regionContigsFasta);
//		}
//		
//		String[] cmd = new String[] { "bash", "-c", "cat " + files.toString() + " >> " + contigFasta };
//		
//		runCommand(cmd);
		
		for (Feature region : regions) {
			String regionContigsFasta = tempDir + "/" + region.getDescriptor() + "_contigs.fasta";
			appendFile(regionContigsFasta, contigFasta);
		}
	}
	*/
	
	public synchronized void addThread(ReAlignerRunnable thread) {
		threads.add(thread);
	}
	
	public synchronized void removeThread(ReAlignerRunnable thread) {
		threads.remove(thread);
	}
	
	private synchronized int activeThreads() {
		return threads.size();
	}
	
	private void waitForAvailableThread() throws InterruptedException {
		while (activeThreads() == numThreads) {
			Thread.sleep(500);
		}
	}
	
	private void waitForAllThreadsToComplete() throws InterruptedException, IOException {
		long start = System.currentTimeMillis();
		while (activeThreads() > 0) {
			long elapsedSecs = (System.currentTimeMillis() - start) / 1000;
			if ((elapsedSecs % 60) == 0) {
				log("Waiting on " + threads.size() + " threads.");
				
				logOSMemory();
				
			}
			Thread.sleep(500);
		}
	}
	
	private void spawnRegionThread(Feature region, String inputSam) throws InterruptedException {
		waitForAvailableThread();
		ReAlignerRunnable thread = new ReAlignerRunnable(this, region, inputSam);
		addThread(thread);
		new Thread(thread).start();
	}
	
	private int countReads(String sam) {
		int count = 0;
		
		SAMFileReader reader = new SAMFileReader(new File(sam));
		reader.setValidationStringency(ValidationStringency.SILENT);

		for (SAMRecord read : reader) {
			count += 1;
		}
		
		reader.close();
		
		return count;
	}
	
	private void downsampleSam(String sam, String downsampledSam, double keepProbability) {
		System.out.println("keepProbability: " + keepProbability);
		
		SAMFileReader reader = new SAMFileReader(new File(sam));
		reader.setValidationStringency(ValidationStringency.SILENT);
		
		SAMFileWriter downsampleOutput = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				samHeader, true, new File(downsampledSam));

		Random random = new Random(RANDOM_SEED);
		int downsampleCount = 0;
		
		for (SAMRecord read : reader) {
			if (random.nextDouble() < keepProbability) {
				downsampleOutput.addAlignment(read);
				downsampleCount += 1;
			}
		}
		
		downsampleOutput.close();
		reader.close();
		
		System.out.println("Downsampled to: " + downsampleCount);
	}
	
	private String getUnalignedReads(String inputSam, String unalignedBam) throws InterruptedException, IOException {
//		String unalignedFastq = getUnalignedFastqFile();
		
		String cmd = "samtools view -b -f 0x04 " + inputSam + " -o " + unalignedBam;
		//TODO: Replace this.
		runCommand(cmd);
		
		int numUnalignedReads = countReads(unalignedBam);
		
		System.out.println("Number of unaligned reads: " + numUnalignedReads);
		
		if (numUnalignedReads > MAX_UNALIGNED_READS) {
			double keepProbability = (double)  MAX_UNALIGNED_READS / (double) numUnalignedReads;
			String downsampledSam = unalignedBam + ".downsampled.bam";
			downsampleSam(unalignedBam, downsampledSam, keepProbability);
			unalignedBam = downsampledSam;
		}
		
		return unalignedBam;
		
//		sam2Fastq(unalignedBam, unalignedFastq);		
	}
	
//	private String getUnalignedFastqFile() {
//		return tempDir + "/unaligned.fastq";
//	}

	public void processRegion(Feature region, String inputSam) throws Exception {
		
		try {
			
	//		log("Extracting targeted region: " + region.getDescriptor());
			String targetRegionBam = extractTargetRegion(inputSam, region, "");
			
			if (this.shouldReprocessUnaligned) {
				String unalignedTargetRegionBam = extractTargetRegion(unalignedRegionSam, region, "unaligned_");
				
				String combinedBam = targetRegionBam.replace(tempDir + "/", tempDir + "/" + "combined_");
//				String combinedBam = "combined_" + targetRegionBam;
				concatenateBams(targetRegionBam, unalignedTargetRegionBam, combinedBam);
				targetRegionBam = combinedBam;
			}
			
			String contigsFasta = tempDir + "/" + region.getDescriptor() + "_contigs.fasta";
			
			Assembler assem = newAssembler();
			
			assem.assembleContigs(targetRegionBam, contigsFasta, region.getDescriptor());
			
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	
	private String extractTargetRegion(String inputSam, Feature region, String prefix)
			throws IOException, InterruptedException {
		
		String extractFile = tempDir + "/" + prefix + region.getDescriptor() + ".bam";
		
		SAMFileWriter outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				samHeader, true, new File(extractFile));
		
		SAMFileReader reader = new SAMFileReader(new File(inputSam));
		reader.setValidationStringency(ValidationStringency.SILENT);
		
		Iterator<SAMRecord> iter = reader.queryOverlapping(region.getSeqname(), (int) region.getStart(), (int) region.getEnd());

		while (iter.hasNext()) {
			SAMRecord read = iter.next();
			outputReadsBam.addAlignment(read);
		}
		
		outputReadsBam.close();
		reader.close();

		return extractFile;
	}

	private String extractTargetRegionOld(String inputSam, Feature region, String prefix)
			throws IOException, InterruptedException {
		String extractFile = tempDir + "/" + prefix + region.getDescriptor() + ".bam";

		String location = region.getSeqname() + ":" + region.getStart() + "-"
				+ region.getEnd();

		String cmd = "samtools view -b " + inputSam + " " + location + " -o "
				+ extractFile;

		System.out.println("Running: [" + cmd + "]");

		long s = System.currentTimeMillis();

		Process proc = Runtime.getRuntime().exec(cmd);

		int ret = proc.waitFor();

		long e = System.currentTimeMillis();

		System.out.println("Extract time: " + (e - s) / 1000 + " seconds.");

		if (ret != 0) {
			String stdout = getOutput(proc.getInputStream());
			String stderr = getOutput(proc.getErrorStream());
			System.out.println("Samtools stdout: " + stdout);
			System.out.println("Samtools stderr: " + stderr);

			throw new RuntimeException(
					"Samtools exited with non-zero return code : " + ret);
		}

		return extractFile;
	}

	private String getOutput(InputStream is) throws IOException {
		StringWriter writer = new StringWriter();

		Reader reader = new BufferedReader(new InputStreamReader(is));

		char[] buffer = new char[1024];

		int n;
		while ((n = reader.read(buffer)) != -1) {
			writer.write(buffer, 0, n);
		}

		reader.close();

		return writer.toString();
	}

	private void loadRegions() throws IOException {
		GtfLoader loader = new GtfLoader();
		regions = loader.load(regionsGtf);
		
		regions = splitRegions(regions);
		
		System.out.println("Regions:");
		for (Feature region : regions) {
			System.out.println(region.getSeqname() + "\t" + region.getStart() + "\t" + region.getEnd());
		}
	}

	public void setRegionsGtf(String gtfFile) {
		this.regionsGtf = gtfFile;
	}

	private void log(String message) {
		long currMillis = System.currentTimeMillis() - startMillis;
		System.out.println(currMillis + " " + message);
	}

	private void getSamHeader(String inputSam) {
		SAMFileReader reader = new SAMFileReader(new File(inputSam));
		reader.setValidationStringency(ValidationStringency.SILENT);

		samHeader = reader.getFileHeader();
		samHeader.setSortOrder(SAMFileHeader.SortOrder.unsorted);

		reader.close();
	}
	
	private void sam2Fastq(String bam, String fastq) throws IOException {
		Sam2Fastq sam2Fastq = new Sam2Fastq();
		sam2Fastq.convert(bam, fastq);
	}
	
	private static int memCnt = 0;
	private void logOSMemory() throws InterruptedException, IOException {
//		String[] cmd = new String[] { "bash", "-c", "echo " + memCnt + " >> memory.txt" };
//		runCommand(cmd);
//		
//		cmd = new String[] { "bash", "-c", "free -g >> memory.txt" };
//		runCommand(cmd);
		
		System.out.println(memCnt++ +
				" mem total: " + Runtime.getRuntime().totalMemory() + 
				", max: " + Runtime.getRuntime().maxMemory() + 
				", free: " + Runtime.getRuntime().freeMemory());
	}
	
//	private void appendFile(String file1, String file2) throws InterruptedException, IOException {
//		String[] cmd = new String[] { "bash", "-c", "cat " + file1 + " >> " + file2 };
//		runCommand(cmd);
//	}
		
	private boolean cleanAndOutputContigs(String contigsSam, String cleanContigsFasta) throws IOException {
		
		boolean hasCleanContigs = false;
		
		BufferedWriter writer = new BufferedWriter(new FileWriter(cleanContigsFasta, false));
		
		SAMFileReader contigReader = new SAMFileReader(new File(contigsSam));
		contigReader.setValidationStringency(ValidationStringency.SILENT);
		
		for (SAMRecord contigRead : contigReader) {
			//TODO: Does this guarantee no alternate alignments?
			if (contigRead.getMappingQuality() > 1) {
				
				removeSoftClips(contigRead);
				
				String bases = contigRead.getReadString();
				
				if (bases.length() >= assemblerSettings.getMinContigLength()) {
					
					// Aligned contigs are already expressed in forward strand context
	//				if (contigRead.getReadNegativeStrandFlag()) {
	//					bases = reverseComplementor.reverseComplement(bases);
	//				}
					
					//TODO: Safer delimiter?  This assumes no ~ in any read
					contigRead.setReadString("");
					String contigReadStr = contigRead.getSAMString();
					contigReadStr = contigReadStr.replace('\t','~');
					
					String contigName = contigRead.getReadName() + "~" + contigReadStr; 
					
					writer.append(">" + contigName);
					writer.append(bases);
					writer.append("\n");
					hasCleanContigs = true;
				}
			}
		}
		contigReader.close();
		
		writer.close();
		
		return hasCleanContigs;
	}
	
	// Assumes entirely soft clipped reads are filtered prior to here.
	// Checks only the first and last Cigar element
	// Does not adjust qualities
	private void removeSoftClips(SAMRecord read) {
		
		Cigar cigar = read.getCigar();
		
		CigarElement firstElement = cigar.getCigarElement(0);
		CigarElement lastElement  = cigar.getCigarElement(cigar.numCigarElements()-1);
		
		if ((firstElement.getOperator() == CigarOperator.S) ||
			(lastElement.getOperator() == CigarOperator.S)) {
		
			Cigar newCigar = new Cigar();
			
			String bases = read.getReadString();
			//String qualities = read.getBaseQualityString();
					
			if (firstElement.getOperator() == CigarOperator.S) {
				bases = bases.substring(firstElement.getLength(), bases.length()-1);
				//qualities = qualities.substring(firstElement.getLength(), qualities.length()-1);
			} else {
				newCigar.add(firstElement);
			}
			
			for (int i=1; i<cigar.numCigarElements()-1; i++) {
				newCigar.add(cigar.getCigarElement(i));
			}
			
			if (lastElement.getOperator() == CigarOperator.S) {
				bases = bases.substring(0, bases.length() - lastElement.getLength() - 1);
				//qualities = qualities.substring(0, qualities.length() - lastElement.getLength() - 1);
			}
			
			read.setCigar(newCigar);
			read.setReadString(bases);
			//read.setBaseQualityString(qualities);
		}
	}
	
	private void alignToContigs(String inputSam, String alignedToContigSam, String contigFasta) throws IOException, InterruptedException {
		// Convert original bam to fastq
		String fastq = tempDir + "/" + "original_reads.fastq";
		sam2Fastq(inputSam, fastq);
		
		// Build contig fasta index
		Aligner contigAligner = new Aligner(contigFasta, numThreads);
		
		contigAligner.index();
		
		// Align region fastq against assembled contigs
		contigAligner.shortAlign(fastq, alignedToContigSam);
	}
	
	static class Pair<T, Y> {
		private T t;
		private Y y;
		public Pair(T t, Y y) {
			this.t = t;
			this.y = y;
		}
		
		public T getFirst() {
			return t;
		}
		
		public Y getSecond() {
			return y;
		}
	}
	
	static class HitInfo {
		private SAMRecord record;
		private int position;
		private char strand;
		private int mismatches;
		
		public HitInfo(SAMRecord record, int position, char strand, int mismatches) {
			this.record = record;
			this.position = position;
			this.strand = strand;
			this.mismatches = mismatches;
		}

		public SAMRecord getRecord() {
			return record;
		}

		public int getPosition() {
			return position;
		}

		public char getStrand() {
			return strand;
		}
		
		public boolean isOnNegativeStrand() {
			return strand == '-';
		}
		
		public int getNumMismatches() {
			return mismatches;
		}
	}
	
	private int getIntAttribute(SAMRecord read, String attribute) {
		Integer num = read.getIntegerAttribute(attribute);
		
		if (num == null) {
			return 0;
		} else {
			return num;
		}
	}
	
	private void adjustReads(String originalReadsSam, String alignedToContigSam, SAMFileWriter outputReadsBam) throws IOException, InterruptedException {
		
//		SAMFileWriter unalignedReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
//				samHeader, true, new File(unalignedSam));
		
		SAMFileReader contigReader = new SAMFileReader(new File(alignedToContigSam));
		contigReader.setValidationStringency(ValidationStringency.SILENT);
		
		SAMFileReader origReader = new SAMFileReader(new File(originalReadsSam));
		origReader.setValidationStringency(ValidationStringency.SILENT);

		Iterator<SAMRecord> contigIter = contigReader.iterator();
		Iterator<SAMRecord> origIter = origReader.iterator();
		
		SAMRecord cachedContig = null;
		
		SamStringReader samStringReader = new SamStringReader();
		
		int ctr = 0;
		
		while ((contigIter.hasNext() || cachedContig != null) && (origIter.hasNext())) {
			
			if ((ctr++ % 100000) == 0) {
				this.logOSMemory();
			}
			
			SAMRecord orig = origIter.next();
			SAMRecord read;
			
			if (cachedContig != null) {
				read = cachedContig;
				cachedContig = null;
			} else {
				read = contigIter.next();
			}
			
			boolean isReadWritten = false;
			
			if (orig.getReadName().equals(read.getReadName())) {
				//TODO: Check for mismatches.  Smarter CIGAR check.
				if ((read.getCigarString().equals("100M")) && (read.getReadUnmappedFlag() == false)) {
				
					SAMRecord origRead = orig;
					String contigReadStr = read.getReferenceName();
					
					
					// Get alternate best hits
					List<HitInfo> bestHits = new ArrayList<HitInfo>();

					contigReadStr = contigReadStr.substring(contigReadStr.indexOf('~')+1);
					contigReadStr = contigReadStr.replace('~', '\t');
					SAMRecord contigRead = samStringReader.getRead(contigReadStr);
					
					int bestMismatches = getIntAttribute(read, "XM");
					
					HitInfo hit = new HitInfo(contigRead, read.getAlignmentStart(),
							read.getReadNegativeStrandFlag() ? '-' : '+', bestMismatches);
					
					bestHits.add(hit);
					
					int numBestHits = getIntAttribute(read, "X0");
					int subOptimalHits = getIntAttribute(read, "X1");
					
					int totalHits = numBestHits + subOptimalHits;
					
					//TODO: If too many best hits, what to do?
					
					if ((totalHits > 1) && (totalHits < 1000)) {
//					if (totalHits < -1000) {
						// Look in XA tag.
						String alternateHitsStr = (String) read.getAttribute("XA");
						if (alternateHitsStr == null) {
							String msg = "best hits = " + numBestHits + ", but no XA entry for: " + read.getSAMString();
							System.out.println(msg);							
							this.missingXATag += 1;
						} else {
							
							String[] alternates = alternateHitsStr.split(";");
							
							for (int i=0; i<alternates.length-1; i++) {
								String[] altInfo = alternates[i].split(",");
								String altContigReadStr = altInfo[0];
								char strand = altInfo[1].charAt(0);
								int position = Integer.parseInt(altInfo[1].substring(1));
								String cigar = altInfo[2];
								int mismatches = Integer.parseInt(altInfo[3]);
								
								if ((cigar.equals("100M")) && (mismatches < bestMismatches)) {
									System.out.println("MISMATCH_ISSUE: " + read.getSAMString());
								}
								
								if ((cigar.equals("100M")) && (mismatches == bestMismatches)) {
									altContigReadStr = altContigReadStr.substring(altContigReadStr.indexOf('~')+1);
									altContigReadStr = altContigReadStr.replace('~', '\t');
									contigRead = samStringReader.getRead(altContigReadStr);
									
									hit = new HitInfo(contigRead, position, strand, mismatches);
									bestHits.add(hit);
								}
							}
						}
					}
					
					// chr_pos_cigar
					Map<String, SAMRecord> outputReadAlignmentInfo = new HashMap<String, SAMRecord>();
					
					for (HitInfo hitInfo : bestHits) {
						
						contigRead = hitInfo.getRecord();
						int position = hitInfo.getPosition() - 1;

						List<ReadBlock> contigReadBlocks = ReadBlock.getReadBlocks(contigRead);
						
						ReadPosition readPosition = new ReadPosition(origRead, position, -1);
						SAMRecord updatedRead = updateReadAlignment(contigRead,
								contigReadBlocks, readPosition);
						
						if (updatedRead != null) {
							//TODO: Move into updateReadAlignment ?
							if (updatedRead.getMappingQuality() == 0) {
								updatedRead.setMappingQuality(1);
							}
							
							if (updatedRead.getReadUnmappedFlag()) {
								updatedRead.setReadUnmappedFlag(false);
							}
							
							updatedRead.setReadNegativeStrandFlag(hitInfo.isOnNegativeStrand());

							// Set read bases to the aligned read (which will be expressed in 
							// forward strand context according to the primary alignment).
							// If this hit's strand is opposite the primary alignment, reverse the bases
							if (hitInfo.isOnNegativeStrand() == read.getReadNegativeStrandFlag()) {
								updatedRead.setReadString(read.getReadString());
								updatedRead.setBaseQualityString(read.getBaseQualityString());
							} else {
								updatedRead.setReadString(reverseComplementor.reverseComplement(read.getReadString()));
								updatedRead.setBaseQualityString(reverseComplementor.reverse(read.getBaseQualityString()));								
							}
							
//							if (hitInfo.isOnNegativeStrand()) {
//								updatedRead.setReadString(reverseComplementor.reverseComplement(read.getReadString()));
//								updatedRead.setBaseQualityString(reverseComplementor.reverse(read.getBaseQualityString()));
//							} else {
//								updatedRead.setReadString(read.getReadString());
//								updatedRead.setBaseQualityString(read.getBaseQualityString());								
//							}
									
//							if ((!origRead.getReadUnmappedFlag()) && (hitInfo.isOnNegativeStrand())) {
//								if (!origRead.getReadNegativeStrandFlag()) {
//									updatedRead.setReadString(reverseComplementor.reverseComplement(updatedRead.getReadString()));
//									updatedRead.setBaseQualityString(reverseComplementor.reverse(updatedRead.getBaseQualityString()));
//								}
//								
//								updatedRead.setReadNegativeStrandFlag(!origRead.getReadNegativeStrandFlag());							
//							}
							
							// Reverse complement / reverse original read bases and qualities if
							// the original read was unmapped and is now on the reverse strand
							// Originally mapped reads would already be expressed in forward strand context
							// TODO: Do we need to handle forward / reverse strand change.  Is this even possible?
							// TODO: What about reads that align across chromosomes and are tagged as unmapped
							//       Might they already be reverse complemented?
//							if ((origRead.getReadUnmappedFlag()) && (hitInfo.isOnNegativeStrand())) {
//								updatedRead.setReadString(reverseComplementor.reverseComplement(updatedRead.getReadString()));
//								updatedRead.setBaseQualityString(reverseComplementor.reverse(updatedRead.getBaseQualityString()));
//								
//								updatedRead.setReadNegativeStrandFlag(hitInfo.isOnNegativeStrand());
//							}
							
							// If the read's alignment info has been modified, record the original alignment.
							if (origRead.getReadUnmappedFlag() ||
								!origRead.getReferenceName().equals(updatedRead.getReferenceName()) ||
								origRead.getAlignmentStart() != updatedRead.getAlignmentStart() ||
								origRead.getReadNegativeStrandFlag() != updatedRead.getReadNegativeStrandFlag() ||
								!origRead.getCigarString().equals(updatedRead.getCigarString())) {
							
								String originalAlignment;
								if (origRead.getReadUnmappedFlag()) {
									originalAlignment = "N/A";
								} else {
									originalAlignment = origRead.getReferenceName() + ":" + origRead.getAlignmentStart() + ":" +
											(origRead.getReadNegativeStrandFlag() ? "-" : "+") + ":" + origRead.getCigarString();
								}
								
								// Read's original alignment position
								updatedRead.setAttribute("YO", originalAlignment);
							}
							
							// Mismatches to the contig
							updatedRead.setAttribute("YM", hitInfo.getNumMismatches());
							
							// Contig's mapping quality
							updatedRead.setAttribute("YC", hitInfo.getRecord().getMappingQuality());
														
							// Check to see if this read has been output with the same alignment already.
							String readAlignmentInfo = updatedRead.getReferenceName() + "_" + updatedRead.getAlignmentStart() + "_" +
									(updatedRead.getReadNegativeStrandFlag() ? "-" : "+") + "_" + updatedRead.getCigarString();
							
							if (!outputReadAlignmentInfo.containsKey(readAlignmentInfo)) {
								outputReadAlignmentInfo.put(readAlignmentInfo, updatedRead);
							}
						}
					}
					
					for (SAMRecord readToOutput : outputReadAlignmentInfo.values()) {
						
						// If the read mapped to multiple locations, set mapping quality to zero.
						if ((outputReadAlignmentInfo.size() > 1) || (totalHits > 1000)) {
							readToOutput.setMappingQuality(0);
						}

						outputReadsBam.addAlignment(readToOutput);
						isReadWritten = true;
					}
				}
				/*
				else {
					if (orig.getReadUnmappedFlag()) {
						unalignedReadsBam.addAlignment(orig);
					}
				}
				*/
			} else {
				cachedContig = read;
			}
			
			if (!isReadWritten) {
				outputReadsBam.addAlignment(orig);
			}
		}

//		unalignedReadsBam.close();
		origReader.close();
		contigReader.close();
		
	}
	
	SAMRecord updateReadAlignment(SAMRecord contigRead,
			List<ReadBlock> contigReadBlocks, ReadPosition orig) {
		List<ReadBlock> blocks = new ArrayList<ReadBlock>();
		SAMRecord read = cloneRead(orig.getRead());

		read.setReferenceName(contigRead.getReferenceName());

		int contigPosition = orig.getPosition();
		int accumulatedLength = 0;

		// read block positions are one based
		// ReadPosition is zero based

		for (ReadBlock contigBlock : contigReadBlocks) {
			if ((contigBlock.getReadStart() + contigBlock.getReferenceLength()) >= orig
					.getPosition() + 1) {
				ReadBlock block = contigBlock.getSubBlock(accumulatedLength,
						contigPosition, read.getReadLength()
								- accumulatedLength);
				
				// If this is an insert, we need to adjust the alignment start
				if ((block.getType() == CigarOperator.I) && (block.getLength() != 0)) {
					contigPosition = contigPosition - (contigBlock.getLength() - block.getLength());
					block.setReferenceStart(block.getReferenceStart() - (contigBlock.getLength() - block.getLength()));
//					block = contigBlock.getSubBlock(accumulatedLength,
//								contigPosition, read.getReadLength()
//								- accumulatedLength);
				}
				
				//TODO: Drop leading and trailing delete blocks

				// TODO: Investigate how this could happen
				if (block.getLength() != 0) {
					blocks.add(block);

					if (block.getType() != CigarOperator.D) {
						accumulatedLength += block.getLength();
					}

					if (accumulatedLength > read.getReadLength()) {
						throw new IllegalStateException("Accumulated Length: "
								+ accumulatedLength
								+ " is greater than read length: "
								+ read.getReadLength());
					}

					if (accumulatedLength == read.getReadLength()) {
						break;
					}
				}
			}
		}

		if (blocks.size() > 0) {
			
			// Don't allow reads to align past end of contig.
//			int cigarLength = ReadBlock.getTotalLength(blocks);
//			if (cigarLength != read.getReadLength()) {
//				read = null;
//			}
			
			// If we've aligned past the end of the contig resulting in a short Cigar
			// length, append additonal M to the Cigar			
			ReadBlock.fillToLength(blocks, read.getReadLength());
			int newAlignmentStart = blocks.get(0).getReferenceStart();
			String newCigar = ReadBlock.toCigarString(blocks);

			read.setCigarString(newCigar);
			read.setAlignmentStart(newAlignmentStart);
		} else {
			// TODO: Investigate how this could happen.
			read = null;
		}

		return read;
	}

	private SAMRecord cloneRead(SAMRecord read) {
		try {
			return (SAMRecord) read.clone();
		} catch (CloneNotSupportedException e) {
			// Infamous "this should never happen" comment here.
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/*
	private void initOutputFile(String outputReadsBamFilename) {
		samHeader.setSortOrder(SAMFileHeader.SortOrder.unsorted);

		outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				samHeader, true, new File(outputReadsBamFilename));
	}
	*/
	
	/**
	 *  If any of the input list of features is greater than maxSize, split them into multiple features. 
	 */
	public List<Feature> splitRegions(List<Feature> regions) {
		List<Feature> splitRegions = new ArrayList<Feature>();
		
		for (Feature region : regions) {
			if (region.getLength() <= MAX_REGION_LENGTH + MIN_REGION_REMAINDER) {
				splitRegions.add(region);
			} else {
				splitRegions.addAll(splitWithOverlap(region));
			}
		}
		
		return splitRegions;
	}
	
	public List<Feature> splitWithOverlap(Feature region) {
		List<Feature> regions = new ArrayList<Feature>();
		
		long pos = region.getStart();
		
		while (pos < region.getEnd()) {
			long start = pos;
			long end = pos + MAX_REGION_LENGTH - REGION_OVERLAP;
			
			// If we're at or near the end of the region, stop at region end.
			if (end > (region.getEnd() - MIN_REGION_REMAINDER)) {
				end = region.getEnd();
			}
			
			pos = end + 1;
			
			regions.add(new Feature(region.getSeqname(), start, end));
		}
		
		return regions;
	}
		
	private Assembler newAssembler() {
		Assembler assem = new Assembler();

		assem.setKmerSize(assemblerSettings.getKmerSize());
		assem.setMinEdgeFrequency(assemblerSettings.getMinEdgeFrequency());
		assem.setMinNodeFrequncy(assemblerSettings.getMinNodeFrequncy());
		assem.setMinContigLength(assemblerSettings.getMinContigLength());
		assem.setMinEdgeRatio(assemblerSettings.getMinEdgeRatio());
		assem.setMaxPotentialContigs(assemblerSettings
				.getMaxPotentialContigs());
		assem.setMinContigRatio(assemblerSettings.getMinContigRatio());
		assem.setMinUniqueReads(assemblerSettings.getMinUniqueReads());

		return assem;
	}
	
	private Assembler newUnalignedAssembler() {
		Assembler assem = new Assembler();

		assem.setKmerSize(assemblerSettings.getKmerSize());
		assem.setMinEdgeFrequency(assemblerSettings.getMinEdgeFrequency() * 2);
		assem.setMinNodeFrequncy(assemblerSettings.getMinNodeFrequncy() * 2);
		assem.setMinContigLength(assemblerSettings.getMinContigLength());
		assem.setMinEdgeRatio(assemblerSettings.getMinEdgeRatio() * 2);
//		assem.setMaxPotentialContigs(assemblerSettings.getMaxPotentialContigs() * 30);
		assem.setMaxPotentialContigs(MAX_POTENTIAL_UNALIGNED_CONTIGS);
		assem.setMinContigRatio(-1.0);
		assem.setMinUniqueReads(assemblerSettings.getMinUniqueReads());

		return assem;
	}


	private void init() {
		File workingDir = new File(tempDir);
		if (workingDir.exists()) {
			if (!workingDir.delete()) {
				throw new IllegalStateException("Unable to delete: " + tempDir);
			}
		}

		if (!workingDir.mkdir()) {
			throw new IllegalStateException("Unable to create: " + tempDir);
		}
		
		File unalignedTempDir = new File(tempDir + "/unaligned");
		
		if (!unalignedTempDir.mkdir()) {
			throw new IllegalStateException("Unable to create: " + tempDir + "/unaligned");
		}
	}

	public void setReference(String reference) {
		this.reference = reference;
	}
	
	public void setReferenceDir(String referenceDir) {
		this.referenceDir = referenceDir;
	}

	public void setTempDir(String temp) {
		this.tempDir = temp;
	}

	public void setAssemblerSettings(AssemblerSettings settings) {
		this.assemblerSettings = settings;
	}
	
	public void setNumThreads(int numThreads) {
		this.numThreads = numThreads;
	}
	
	public void setMinContigMapq(int minContigMapq) {
		this.minContigMapq = minContigMapq;
	}
	
	public void setAllowedMismatchesFromContig(int allowedMismatchesFromContig) {
		this.allowedMismatchesFromContig = allowedMismatchesFromContig;
	}
	
	public void setShouldReprocessUnaligned(boolean shouldReprocessUnaligned) {
		this.shouldReprocessUnaligned = shouldReprocessUnaligned;
	}

	public static void run(String[] args) throws Exception {
		
		System.out.println("Starting...");
		
		ReAlignerOptions options = new ReAlignerOptions();
		options.parseOptions(args);

		if (options.isValid()) {

			AssemblerSettings assemblerSettings = new AssemblerSettings();

			assemblerSettings.setKmerSize(options.getKmerSize());
			assemblerSettings.setMinContigLength(options.getMinContigLength());
			assemblerSettings
					.setMinEdgeFrequency(options.getMinEdgeFrequency());
			assemblerSettings.setMinNodeFrequncy(options.getMinNodeFrequency());
			assemblerSettings.setMinEdgeRatio(options.getMinEdgeRatio());
			assemblerSettings.setMaxPotentialContigs(options
					.getMaxPotentialContigs());
			assemblerSettings.setMinContigRatio(options.getMinContigRatio());
			assemblerSettings.setMinUniqueReads(options.getMinUniqueReads());

			ReAligner realigner = new ReAligner();
			realigner.setReference(options.getReference());
			realigner.setReferenceDir(options.getReferenceDir());
			realigner.setRegionsGtf(options.getTargetRegionFile());
			realigner.setTempDir(options.getWorkingDir());
			realigner.setAssemblerSettings(assemblerSettings);
			realigner.setNumThreads(options.getNumThreads());
			realigner.setMinContigMapq(options.getMinContigMapq());
			realigner.setAllowedMismatchesFromContig(options.getAllowedMismatchesFromContig());
			realigner.setShouldReprocessUnaligned(!options.isSkipUnalignedAssembly());
			realigner.setUseSmallAlignerIndex(options.useSmallAlignerIndex());

			long s = System.currentTimeMillis();

			realigner.reAlign(options.getInputFile(), options.getOutputFile());

			long e = System.currentTimeMillis();

			System.out.println("Elapsed seconds: " + (e - s) / 1000);
		}
	}
	
/*
	public static void main(String[] args) throws Exception {
		ReAligner realigner = new ReAligner();
//		String originalReadsSam = args[0];
//		String alignedToContigSam = args[1];
//		String unalignedSam = args[2];
//		String outputFilename = args[3];
		
		String originalReadsSam = "/home/lmose/dev/ayc/sim/s43/orig_atc.bam";
		String alignedToContigSam = "/home/lmose/dev/ayc/sim/s43/atc.bam";
//		String unalignedSam = args[2];
		String outputFilename = "/home/lmose/dev/ayc/sim/s43/atc_out.bam";
		
		realigner.getSamHeader(originalReadsSam);
		
		SAMFileWriter outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				realigner.samHeader, true, new File(outputFilename));
		
		realigner.adjustReads(originalReadsSam, alignedToContigSam, outputReadsBam);
		
		outputReadsBam.close();
	}
*/
	
	public static void main(String[] args) throws Exception {
		ReAligner realigner = new ReAligner();

		long s = System.currentTimeMillis();

/*
		String input = "/home/lisle/ayc/sim/sim261/chr1/sorted.bam";
		String output = "/home/lisle/ayc/sim/sim261/chr1/realigned.bam";
		String reference = "/home/lisle/reference/chr1/chr1.fa";
		String regions = "/home/lisle/ayc/regions/chr1_261.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim261/chr1/working";
*/
		
/*
		String input = "/home/lisle/ayc/sim/sim261/chr17/sorted.bam";
		String output = "/home/lisle/ayc/sim/sim261/chr17/realigned.bam";
		String reference = "/home/lisle/reference/chr17/chr17.fa";
		String regions = "/home/lisle/ayc/regions/chr17_261.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim261/chr17/working";
*/		
	
//		String input = "/home/lmose/dev/ayc/sim/sim261/chr11/sorted.bam";
//		String output = "/home/lmose/dev/ayc/sim/sim261/chr11/realigned.bam";
//		String reference = "/home/lmose/reference/chr11/chr11.fa";
//		String regions = "/home/lmose/dev/ayc/regions/chr11_261.gtf";
//		String tempDir = "/home/lmose/dev/ayc/sim/sim261/chr11/working";
		
		String input = "/home/lmose/dev/ayc/sim/sim80/sorted_rainbow.bam";
		String output = "/home/lmose/dev/ayc/sim/sim80/rainbow_realigned.bam";
		String reference = "/home/lmose/reference/chr16/chr16.fa";
		String regions = "/home/lmose/dev/ayc/regions/clinseq5/rainbow.gtf";
		String tempDir = "/home/lmose/dev/ayc/sim/sim80/rainbow_working";

		

		/*
		String input = "/home/lmose/dev/ayc/sim/38/sorted_tiny.bam";
		String output = "/home/lmose/dev/ayc/sim/38/realigned.bam";
		String reference = "/home/lmose/reference/chr7/chr7.fa";
		String regions = "/home/lmose/dev/ayc/regions/egfr.gtf";
		String tempDir = "/home/lmose/dev/ayc/sim/38/working";
*/

		
/*
		String input = "/home/lisle/ayc/sim/sim261/chr13/sorted.bam";
		String output = "/home/lisle/ayc/sim/sim261/chr13/realigned.bam";
		String reference = "/home/lisle/reference/chr13/chr13.fa";
		String regions = "/home/lisle/ayc/regions/chr13_261.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim261/chr13/working";
*/
		
/*		
		String input = "/home/lisle/ayc/sim/sim261/chr16/sorted.bam";
		String output = "/home/lisle/ayc/sim/sim261/chr16/realigned.bam";
		String reference = "/home/lisle/reference/chr16/chr16.fa";
		String regions = "/home/lisle/ayc/regions/chr16_261.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim261/chr16/working";
*/
		
/*
		String input = "/home/lisle/ayc/sim/sim261/sorted.bam";
		String output = "/home/lisle/ayc/sim/sim261/realigned.bam";
		String reference = "/home/lisle/reference/chr13/chr13.fa";
		String regions = "/home/lisle/ayc/regions/chr13_261.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim261/working";
*/
/*		
		String input = "/home/lisle/ayc/sim/sim1/bug/chr8_141889351_141889791.bam";
		String output = "/home/lisle/ayc/sim/sim1/bug/realigned.bam";
		String reference = "/home/lisle/reference/chr8/chr8.fa";
		String regions = "/home/lisle/ayc/regions/chr8_141889351_141889791.gtf";
		String tempDir = "/home/lisle/ayc/sim/sim1/bug/working";
*/


		AssemblerSettings settings = new AssemblerSettings();
		settings.setKmerSize(63);
		settings.setMinContigLength(100);
		settings.setMinEdgeFrequency(6);
		settings.setMinNodeFrequncy(6);
		settings.setMinEdgeRatio(.05);
		settings.setMaxPotentialContigs(30000);
		settings.setMinContigRatio(.3);
		settings.setMinUniqueReads(1);

		realigner.setAssemblerSettings(settings);
		
		realigner.setMinContigMapq(1);
		realigner.setAllowedMismatchesFromContig(2);
		realigner.setReference(reference);
		realigner.setRegionsGtf(regions);
		realigner.setTempDir(tempDir);
		realigner.setNumThreads(2);

		realigner.reAlign(input, output);

		long e = System.currentTimeMillis();

		System.out.println("Elapsed seconds: " + (e - s) / 1000);
	}
}
