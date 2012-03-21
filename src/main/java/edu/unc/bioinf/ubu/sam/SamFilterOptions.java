package edu.unc.bioinf.ubu.sam;

import joptsimple.OptionParser;
import edu.unc.bioinf.ubu.util.Options;

public class SamFilterOptions extends Options {
    private static final String INPUT_FILE = "in";
    private static final String OUTPUT_FILE = "out";
    private static final String STRIP_INDELS = "strip-indels";
    private static final String MAX_INSERT_LEN = "max-insert";
    
	private OptionParser parser;
	private boolean isValid;
	
	@Override
	protected OptionParser getOptionParser() {
    	if (parser == null) {
            parser = new OptionParser();
            parser.accepts(INPUT_FILE, "Required input sam or bam file").withRequiredArg().ofType(String.class);
            parser.accepts(OUTPUT_FILE, "Required output sam or bam file").withRequiredArg().ofType(String.class);
            parser.accepts(STRIP_INDELS, "If specified, discard read pairs containing indels from output (default off)");
            parser.accepts(MAX_INSERT_LEN, "If specified, discard clusters greater than specified insert length").withRequiredArg().ofType(Integer.class);
    	}
    	
    	return parser;
	}

	@Override
	protected void validate() {
        isValid = true;
        
        if (!getOptions().hasArgument(INPUT_FILE)) {
            isValid = false;
            System.err.println("Missing required input SAM/BAM file");
        }
        
        if (!getOptions().hasArgument(OUTPUT_FILE)) {
            isValid = false;
            System.err.println("Missing required output SAM/BAM file");
        }
        
        if ((!getOptions().has(STRIP_INDELS)) && (!getOptions().hasArgument(MAX_INSERT_LEN))) {
        	isValid = false;
        	System.err.println("At least one filtering option must be specified");
        }
        
        if (!isValid) {
            printHelp();
        }
	}
	
	public String getInputFile() {
		return (String) getOptions().valueOf(INPUT_FILE);
	}
	
	public String getOutputFile() {
		return (String) getOptions().valueOf(OUTPUT_FILE);
	}
	
	public boolean shouldStripIndels() {
		return getOptions().has(STRIP_INDELS);
	}
	
	public int getMaxInsertLen() {
		int maxLen = -1;
		
		if (getOptions().hasArgument(MAX_INSERT_LEN)) {
			maxLen = (Integer) getOptions().valueOf(MAX_INSERT_LEN);
		}
		
		return maxLen;
	}
	
    public boolean isValid() {
        return isValid;
    }

}
