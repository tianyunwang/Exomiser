package de.charite.compbio.exomiser.db.build.parsers;

import de.charite.compbio.exomiser.core.Constants;
import de.charite.compbio.exomiser.db.build.reference.VariantPathogenicity;
import de.charite.compbio.exomiser.db.build.resources.Resource;
import de.charite.compbio.exomiser.db.build.resources.ResourceOperationStatus;
import de.charite.compbio.jannovar.io.ReferenceDictionary;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parse information from the NSFP chromosome files. Create an SQL dump file
 * that will be used to import the information into the postgreSQL database.
 * <P>
 * Note that for some SNVs, there are multiple lines in the dbSNFP file. This 
 * parser takes them all.
 * <P>
 * The annotations of the dbNSFP fields are from the dbNSFP documentation. The 
 * parser uses a small sub-set of fields from the file. These are declared 
 * initially as what currently works, but the parser will also try to auto-detect
 * them.
 * <P>
 * The structure of the <B>variant.pg</B> file is then (example line):</BR>
 * 6|345879|A|G|K|E|72|72|0.52|0.002|0.734868|0.278|-5|-5.0|2 </BR>
 * <UL>
 * <LI>6: chromosome
 * <LI>345879: position
 * <LI>A: ref nucleotide
 * <LI>G: alt nucleotide
 * <LI>K: aaref (reference amino acid)
 * <LI>E: aaalt (alternate amino acid)
 * <LI>72: uniprot_aapos
 * <LI>72: aapos (position of mutation in amino acid sequence)
 * <LI>0.52: sift score
 * <LI>0.002: polyphen2_HVAR
 * <LI>0.734868: mut_taster score
 * <LI>0.278: phyloP score
 * <LI>-5: ThGenom_AC, 1000G allele count (Note -5 is a flag that we could not
 * find data)
 * <LI>-5.0: ThGenom_AF, 1000G allele frequency (Note -5.0 is a flag that we
 * could not find data)
 * <LI>2: gene_id_key. This is the primary key of the gene table
 * (auto_increment, see above).
 *
 * @author Peter N. Robinson
 * @version 0.06 (15 July 2013)
 */
public class NSFP2SQLDumpParser implements ResourceParser {

    private static final Logger logger = LoggerFactory.getLogger(NSFP2SQLDumpParser.class);

    // The following are the fields of the dbNSFP files.
    /**
     * Chromosome number
     */
    private static int CHR = 0;
    /**
     * physical position on the chromosome as to hg19 (1-based coordinate)
     */
    private static int POS = 1;
    /**
     * reference nucleotide allele (as on the + strand)
     */
    private static int REF = 2;
    /**
     * alternative nucleotide allele (as on the + strand)
     */
    private static int ALT = 3;
    /**
     * reference amino acid or "." if the variant is a splicing site SNP (2bp on
     * each end of an intron)
     */
    private static int AAREF = 4;
    /**
     * alternative amino acid, or "." if the variant is a splicing site SNP (2bp
     * on each end of an intron)
     */
    private static int AAALT = 5;
    /**
     * physical position on the chromosome as to hg18 (1-based coordinate)
     */
//    public static final int HG18_POS = 6;
    /**
     * gene name (gene symbol)
     */
    private static int GENENAME = 7;

    /**
     * amino acid position as to the protein. "-1" if the variant is a splicing
     * site SNP (2bp on each end of an intron)
     */
    private static int AAPOS = 20;
    /**
     * SIFT score, If a score is smaller than 0.05 the corresponding NS is
     * predicted as "D(amaging)"; otherwise it is predicted as "T(olerated)".
     */
    private static int SIFT_SCORE = 23;
    /**
     * SIFT_score_converted: SIFTnew=1-SIFTori. The larger the more damaging.
     * Currently unused in Exomiser.
     */
//    public static final int SIFT_SCORE_CONVERTED = 24;
 
    /**
     * Polyphen2 score based on HumVar, i.e. hvar_prob.
     * <P>
     * The score ranges from 0 to 1, and the corresponding prediction is
     * "probably damaging" if it is in [0.909,1]; "possibly damaging" if it is
     * in [0.447,0.908]; "benign" if it is in [0,0.446]. Score cutoff for binary
     * classification is 0.5, i.e. the prediction is "neutral" if the score is
     * smaller than 0.5 and "deleterious" if the score is larger than 0.5.
     * Multiple entries separated by ";".
     */
    private static int POLYPHEN2_HVAR_SCORE = 29;//28

    /**
     * MutationTaster score
     */
    private static int MUTATION_TASTER_SCORE = 35;//33

    /**
     * MutationTaster prediction, "A" ("disease_causing_automatic"), "D"
     * ("disease_causing"), "N" ("polymorphism") or "P"
     * ("polymorphism_automatic"). Note that the score represents the calculated
     * probability that the prediction is correct. Thus, if the prediction is
     * "N" or "P", we set the mutation score to zero. If the score is "A" or
     * "D", we report the score as given in dbNSFP.
     */
    private static int MUTATION_TASTER_PRED = 37;//35

    /**
     * PhyloP score, the larger the score, the more conserved the site.
     */
    private static int PHYLO_P = 59;//50
    /// End of list of field indices for dbNSFP

    /**
     * Total number of fields in the dbNSFP database
     */
    private static int N_NSFP_FIELDS = 86;//59
    
    private static int CADD_raw = 51;
    
    private static int CADD_raw_rankscore = 52;
    
    /**
     * This variable will contain values such as A3238732G that represent the
     * current SNV. It will be used to deal with doubled lines for the same
     * variant
     */
    private String currentVar = null;

    /**
     * A number that will be used as a primary key in the gene file (like an
     * auto increment in MySQL).
     */
    private int autoIncrement = 0;
    /**
     * The count of all lines parsed from all of the dbNSFP files (Header lines
     * are not counted).
     */
    private int totalLinesCount = 0;
    /**
     * The count of all variants added to the dump file. Note, multiple lines
     * for same variant are counted once.
     */
    private int totalVariantsCount = 0;
    /**
     * The count of all the genes added to the dump file.
     */
    private int totalGenesCount = 0;

    /** the reference dictionary to use for chromosome name to numeric id conversion */
    private final ReferenceDictionary refDict;

    /**
     * Get count of all lines parsed from all of the dbNSFP files (Header lines
     * are not counted).
     */
    public int getTotalNsfpLines() {
        return totalLinesCount;
    }

    /**
     * Get count of all variants added to the dump file. Note, multiple lines
     * for same variant are counted once.
     */
    public int getVariantCount() {
        return totalVariantsCount;
    }

    /**
     * Get count of all the genes added to the dump file.
     */
    public int getGeneCount() {
        return totalGenesCount;
    }

    /**
     * The constructor initializes the File output streams.
     * 
     * @param refDict
     *            the {@link ReferenceDictionary} to use for converting contig names to numeric ids
     */
    public NSFP2SQLDumpParser(ReferenceDictionary refDict) {
        this.refDict = refDict;
    }

    @Override
    public void parseResource(Resource resource, Path inDir, Path outDir) {

        Path inFile = inDir.resolve(resource.getExtractedFileName());
        Path outFile = outDir.resolve(resource.getParsedFileName());

        logger.info("Parsing {} file: {}. Writing out to: {}", resource.getName(), inFile, outFile);
        ResourceOperationStatus status;
        
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(inFile.toString()));
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipInputStream));
                BufferedWriter writer = Files.newBufferedWriter(outFile, Charset.defaultCharset())) {
            
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (zipEntry.getName().contains("_variant.chr")) {
                    logger.info("Parsing variant chromosome file: {}. Parsed {} variants so far...", zipEntry.getName(), totalLinesCount);
                    
                    writer.flush();
                    
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        totalLinesCount++;
                        if (line.startsWith("#")) {
                            //try to autodetect the column positions for the parser 
                            setParseFields(line);                          
                        } else {
                            VariantPathogenicity pathogenicity = parseLine(line);
                            writer.write(pathogenicity.toDumpLine());
                        }
                    }
                }
            }
        status = ResourceOperationStatus.SUCCESS;
            
        } catch (FileNotFoundException ex) {
            logger.error(null, ex);
            status = ResourceOperationStatus.FILE_NOT_FOUND;
        } catch (IOException ex) {
            logger.error(null, ex);
            status = ResourceOperationStatus.FAILURE;
        }

        resource.setParseStatus(status);
        logger.info("{}", status);
    }

    /**
     * Parses the dbNSFP variant lines for the pathogenicity scores.
     *
     * @param line
     * @return
     */
    VariantPathogenicity parseLine(String line) {

        String[] fields = line.split("\t");
        if (fields.length < N_NSFP_FIELDS) {
            logger.error("Malformed line '{}' - Only {} fields found (expecting {})", line, fields.length, N_NSFP_FIELDS);
            System.exit(1);
        }

        String chr = fields[CHR];

        int c = refDict.contigID.get(chr);

        int pos = Integer.parseInt(fields[POS]);
        char ref = fields[REF].charAt(0);
        char alt = fields[ALT].charAt(0);
        char aaref = fields[AAREF].charAt(0);
        char aaalt = fields[AAALT].charAt(0);
        int aapos = parseAaPos(fields[AAPOS]);
        float sift = getMostPathogenicSIFTScore(fields[SIFT_SCORE]);
        float polyphen2_HVAR = getMostPathogenicPolyphenScore(fields[POLYPHEN2_HVAR_SCORE]);
        float mut_taster = getMostPathogenicMutTasterScore(fields[MUTATION_TASTER_SCORE], fields[MUTATION_TASTER_PRED]);
        float phyloP = parsePhyloP(fields[PHYLO_P]);
        float cadd_raw = parseCaddRaw(fields[CADD_raw]);
        float cadd_raw_rankscore = parseCaddRawRankScore(fields[CADD_raw_rankscore]);
        //float cadd_phred = parseCaddPhred(fields[CADD_phred]);

        return new VariantPathogenicity(c, pos, ref, alt, aaref, aaalt, aapos,
                sift, polyphen2_HVAR, mut_taster,
                phyloP, cadd_raw_rankscore, cadd_raw);
    }

    /**
     * Many entries in dbNFSP are lists of transcripts separated by ";" For
     * instance, ENST00000298232;ENST00000361285;ENST00000342420</BR>
     * This is the case for genes with multiple transcripts. For simplicity, we
     * will just take the first such entry.
     */
    private String first_entry(String s) {
        int i = s.indexOf(";");
        if (i > 0) {
            s = s.substring(0, i);
        }
        return s;
    }

    /**
     * Some entries in dbNSFP are either nonnegative ints or "." . If the
     * latter, then return -1 (NOPARSE; a flag)
     */
    private int parseAaPos(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE;
        }
        Integer ret_value;
        int i = s.indexOf(";");
        if (i > 0) {
            s = s.substring(0, i);
        }
        try {
            ret_value = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            logger.error("Could not parse aapos value: '{}'", s);
            return Constants.NOPARSE;
        }
        return ret_value;
    }

    /**
     * Some entries in dbNSFP are either nonnegative floats or "." . If the
     * latter, then return -1f (NOPARSE_FLOAT; a flag)
     */
    private float parsePhyloP(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        int i = s.indexOf(";");
        if (i > 0) {
            s = s.substring(0, i);
        }
        float value;
        try {
            value = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            logger.error("Could not parse phyloP float value: '{}'", s);
            return Constants.NOPARSE_FLOAT;
        }
        return value;
    }
    
       /**
     * Some entries in dbNSFP are either nonnegative floats or "." . If the
     * latter, then return -1f (NOPARSE_FLOAT; a flag)
     */
    private float parseCaddRawRankScore(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        int i = s.indexOf(";");
        if (i > 0) {
            s = s.substring(0, i);
        }
        float value;
        try {
            value = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            logger.error("Could not parse CaddRawRankScore float value: '{}'", s);
            return Constants.NOPARSE_FLOAT;
        }
        return value;
    }

          /**
     * Some entries in dbNSFP are either nonnegative floats or "." . If the
     * latter, then return -1f (NOPARSE_FLOAT; a flag)
     */
    private float parseCaddRaw(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        int i = s.indexOf(";");
        if (i > 0) {
            s = s.substring(0, i);
        }
        float value;
        try {
            value = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            logger.error("Could not parse CaddRaw float value: '{}'", s);
            return Constants.NOPARSE_FLOAT;
        }
        return value;
    }
    
    /**
     * If there are SIFT scores for two different transcripts that correspond to
     * a given chromosomal variant, they are entered e.g. as 0.527;0.223. In
     * this case, we will extract the most pathogenic score, i.e., the score
     * that is closest to zero.
     *
     * @param s SIFT score, either a single float number or a semicolon
     * separated list of such scores
     * @return A float representation of the SIFT score. If a list of SIFT
     * scores is passed to the function, then a float representation of the most
     * pathogenic score is returned. If "." is passed to the function, then
     * return NOPARSE_FLOAT (a flag)
     */
    private float getMostPathogenicSIFTScore(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        float min = Float.MAX_VALUE;
        String[] A = s.split(";");
        for (String a : A) {
            a = a.trim();
             // Note there are some entries such as ".;0.292"
            if (a.equals(".")) {
                continue;
            }
            try {
                float value = Float.parseFloat(a);
                if (min > value) {
                    min = value;
                }
            } catch (NumberFormatException e) {
                logger.error("Could not parse sift score: '{}'", s);
                return Constants.NOPARSE_FLOAT;
            }
        }
        if (min < Float.MAX_VALUE) {
            return min;
        } else {
            return Constants.NOPARSE_FLOAT;
        }
    }

    /**
     * If there are Polyphen scores for two different transcripts that
     * correspond to a given chromosomal variant, they are entered e.g. as
     * 0.527;0.223. In this case, we will extract the most pathogenic score,
     * i.e., the score that is closest to one.
     *
     * @param s Polyphen score, either a single float number or a semicolon
     * separated list of such scores
     * @return A float representation of the Polyphen score. If a list of
     * Polyphen scores is passed to the function, then a float representation of
     * the most pathogenic score is returned. If "." is passed to the function,
     * then return NOPARSE_FLOAT (a flag)
     */
    private float getMostPathogenicPolyphenScore(String s) {
        if (s.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        float max = Float.MIN_VALUE;
        String[] A = s.split(";");
        for (String a : A) {
            a = a.trim();
             // Note there are some entries such as ".;0.292"
            if (a.equals(".")) {
                continue;
            }
            try {
                float value = Float.parseFloat(a);
                if (max < value) {
                    max = value;
                }
            } catch (NumberFormatException e) {
                logger.error("Could not parse polyPhen score value: '{}'", s);
                return Constants.NOPARSE_FLOAT;
            }
        }
        if (max > Float.MIN_VALUE) {
            return max;
        } else {
            return Constants.NOPARSE_FLOAT;
        }
    }

    /**
     * If there are Mutation Taster scores for two different transcripts that
     * correspond to a given chromosomal variant, they are entered e.g. as
     * 0.527;0.223. In this case, we will extract the most pathogenic score,
     * i.e., the score that is closest to one. Note that this function is
     * identical to the function for poylphen scores since both have scores
     * [0..1] with scores closer to 1 being more pathogenic. We keep a second
     * function since the way the various scores are normalized in dbNSFP may
     * change in the future.
     *
     * @param score Mutation Taster score, either a single float number or a
     * semicolon separated list of such scores
     * @param prediction MutationTaster prediction. If this is for a
     * polymorphism, then the score is set to zero (not path).
     * @return A float representation of the Mutation Taster score. If a list of
     * Mutation Taster scores is passed to the function, then a float
     * representation of the most pathogenic score is returned. If "." is passed
     * to the function, then return NOPARSE_FLOAT (a flag)
     */
    private float getMostPathogenicMutTasterScore(String score, String prediction) {
        if (score.equals(".")) {
            return Constants.NOPARSE_FLOAT;
        }
        float max = Float.MIN_VALUE;
        String[] A = score.split(";");
        String[] pred = prediction.split(";");
        if (A.length != pred.length) {
            logger.error("Badly formated mutation taster score entry: Score was: {} and prediction was {}", score, prediction);
            logger.error("Length of score entry: {}, length of prediction entry: {}", A.length, pred.length);
            return Constants.NOPARSE_FLOAT;
        }
        for (int i = 0; i < A.length; ++i) {
            String a = A[i].trim();
            String p = pred[i].trim();
            if (p.equals("N") || p.equals("P")) {
                max = 0f;
                continue;
            }
            if (!p.equals("A") && !p.equals("D")) {
                logger.error("Badly formated mutation taster score entry. The prediction field was '{}'", p);
                logger.error("Acceptable values for prediction field are one of A,D,N,P");
                return Constants.NOPARSE_FLOAT;
            }
            if (a.equals(".")) { /* Note there are some entries such as ".;0.292" */

                continue;
            }
            try {
                float value = Float.parseFloat(a);
                if (max < value) {
                    max = value;
                }
            } catch (NumberFormatException e) {
                logger.error("Could not parse mutTaster score: '{}'", score);
                return Constants.NOPARSE_FLOAT;
            }
        }
        if (max > Float.MIN_VALUE) {
            return max;
        } else {
            return Constants.NOPARSE_FLOAT;
        }
    }

    /**
     * Sets the parser fields so that if the column positions change (this is a 
     * common occurrence apparently), then the parser will adapt itself accordingly. 
     * Note that if the column names change this will break the parser.
     * @param line 
     */
    private void setParseFields(String line) {
        //remove the initial '#' character from the header line
        line = line.substring(1);
        //then split 
        String[] fields = line.split("\t");
        
        N_NSFP_FIELDS = fields.length;
        
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            logger.debug("Field {} = {}", i, field);
            int prev = 0;
            switch (field){
                case "chr":
                    prev = CHR;
                    logger.info("Setting CHR field '{}' from position {} to {}", field, prev, i);
                    CHR = i;
                    break;
                case "ref":
                    prev = REF;
                    logger.info("Setting REF field '{}' from position {} to {}", field, prev, i);
                    REF = i;
                    break;
                case "alt":
                    prev = ALT;
                    logger.info("Setting ALT field '{}' from position {} to {}", field, prev, i);
                    ALT = i;
                    break;
                case "aaref":
                    prev = AAREF;
                    logger.info("Setting AAREF field '{}' from position {} to {}", field, prev, i);
                    AAREF = i;
                    break;
                case "aaalt":
                    prev = AAALT;
                    logger.info("Setting AAALT field '{}' from position {} to {}", field, prev, i);
                    AAALT = i;
                    break;
                case "genename":
                    prev = GENENAME;
                    logger.info("Setting GENENAME field '{}' from position {} to {}", field, prev, i);
                    GENENAME = i;
                    break;
                case "aapos":
                    prev = AAPOS;
                    logger.info("Setting AAPOS field field '{}' from position {} to {}", field, prev, i);
                    AAPOS = i;
                    break;
                case "SIFT_score":
                    prev = SIFT_SCORE;
                    logger.info("Setting SIFT_SCORE field '{}' from position {} to {}", field, prev, i);
                    SIFT_SCORE = i;
                    break;
                case "Polyphen2_HVAR_score":
                    prev = POLYPHEN2_HVAR_SCORE;
                    logger.info("Setting POLYPHEN2_HVAR_SCORE field '{}' from position {} to {}", field, prev, i);
                    POLYPHEN2_HVAR_SCORE = i;
                    break;
                case "MutationTaster_score":
                    prev = MUTATION_TASTER_SCORE;
                    logger.info("Setting MUTATION_TASTER_SCORE field '{}' from position {} to {}", field, prev, i);
                    MUTATION_TASTER_SCORE = i;
                    break;
                case "MutationTaster_pred":
                    prev = MUTATION_TASTER_PRED;
                    logger.info("Setting MUTATION_TASTER_PRED field '{}' from position {} to {}", field, prev, i);
                    MUTATION_TASTER_PRED = i;
                    break;
                case "phyloP":
                    prev = PHYLO_P;
                    logger.info("Setting PHYLO_P field '{}' from position {} to {}", field, prev, i);
                    PHYLO_P = i;
                    break;
                case "CADD_raw":
                    prev = CADD_raw;
                    logger.info("Setting CADD_raw field '{}' from position {} to {}", field, prev, i);
                    CADD_raw = i;
                    break;
                case "CADD_raw_rankscore":
                    prev = CADD_raw_rankscore;
                    logger.info("Setting CADD_raw_rankscore field '{}' from position {} to {}", field, prev, i);
                    CADD_raw_rankscore = i;
                    break;
            }                    
        }
    }

}
