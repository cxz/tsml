/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package evaluation;

import fileIO.OutFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import utilities.DebugPrinting;
import utilities.GenericTools;
import utilities.InstanceTools;
import utilities.generic_storage.Pair;

/**
 * This is a container class for the storage of predictions and meta-info of a 
 * classifier on a single set of instances (for example, the test set of a particular 
 * resample of a particular dataset). 
 * 
 * Predictions can be stored via addPrediction(...) or addAllPredictions(...)
 * Currently, the information stored about each prediction is: 
 *    - The true class value                            (double   getTrueClassValue(index))
 *    - The predicted class value                       (double   getPredClassValue(index))
 *    - The probability distribution for this instance  (double[] getProbabilityDistribution(index))
 *    - The time taken to predict this instance id      (long     getPredictionTime(index))
 *    - An optional description of the prediction       (String   getPredDescription(index))
 *
 * The meta info stored is:
 *  [LINE 1 OF FILE] 
 *    - get/setDatasetName(String)
 *    - get/setClassifierName(String)
 *    - get/setSplit(String)
 *    - get/setFoldId(String)
 *    - get/setDescription(String)
 *    - get/setTimeUnit(TimeUnit)
 *  [LINE 2 OF FILE]
 *    - get/setParas(String)
 *  [LINE 3 OF FILE]
 *    - getAccuracy() (calculated from predictions, not settable)
 *    - get/setBuildTime(long)
 *    - get/setTestTime(long)
 *    - get/setMemory(long)
 *  [REMAINING LINES: PREDICTIONS]
 *    - trueClassVal, predClassVal,[empty], dist[0], dist[1] ... dist[c],[empty], predTime, [empty], predDescription
 * 
 * Supports reading/writing of results from/to file, in the 'classifierResults file-format'
 *    - loadResultsFromFile(String path)
 *    - writeToFile(String path)
 * 
 * Supports recording of timings in different time units, however nano seconds is default and 
 * typically preferred. Older files that are read in and do not have a time unit specified are 
 * assumed to be in milliseconds, as that was the old default.
 *
 * Also supports the calculation of various evaluative performance metrics  based on the results (accuracy, 
 * auroc, nll etc.) which are used in the MultipleClassifierEvaluation pipeline. For now, call
 * findAllStats() to calculate the performance metrics based on the stored predictions, and access them 
 * via the appropriate get methods.
 * 
 * @author James Large (james.large@uea.ac.uk) + edits from just about everybody
 * @date 19/02/19
 */
public class ClassifierResults implements DebugPrinting, Serializable{
        
//LINE 1: meta info, set by user
    private String classifierName = "";
    private String datasetName = "";
    private int foldID = -1;
    private String split = ""; //e.g train or test
    private String description= ""; //human-friendly optional extra info if wanted. 
    
//LINE 2: classifier setup/info, parameters. precise format is up to user. 
    //e.g maybe this line includes the accuracy of each parameter set searched for in a tuning process, etc
    //old versions of file format also include build time.
    private String paras = "No parameter info";
    
//LINE 3: acc, buildTime, testTime, memoryUsage
    //simple summarative performance stats. 
    
    /**
     * Calculated from the stored predictions, cannot be explicitly set by user
     */
    private double acc = -1; 
    
    /**
     * The time taken to complete buildClassifier(Instances), aka training. May be cumulative time over many parameter set builds, etc
     * 
     * It is assumed that the time given will be in the unit of measurement set by this object TimeUnit, default nanoseconds. 
     * If no benchmark time is supplied, the default value is -1
     */
    private long buildTime = -1; 
    
    /**
     * The cumulative prediction time, equal to the sum of the individual prediction times stored. Intended as a quick helper/summary 
     * in case complete prediction information is not stored, and/or for a human reader to quickly compare times. 
     * 
     * It is assumed that the time given will be in the unit of measurement set by this object TimeUnit, default nanoseconds. 
     * If no benchmark time is supplied, the default value is -1
     */
    private long testTime = -1; //total testtime for all predictions
        
    /**
     * The time taken to perform some standard benchmarking operation, to allow for a (not necessarily precise) 
     * way to measure the general speed of the hardware that these results were made on, such that users 
     * analysing the results may scale the timings in this file proportional to the benchmarks to get a consistent relative scale 
     * across different results sets. It is up to the user what this benchmark operation is, and how long it is (roughly) expected to take. 
     * 
     * It is assumed that the time given will be in the unit of measurement set by this object TimeUnit, default nanoseconds. 
     * If no benchmark time is supplied, the default value is -1
     */
    private long benchmarkTime = -1; 
    
    /**
     * It is user dependent on exactly what this field means and how accurate it may be (because of Java's lazy gc). 
     * Intended purpose would be the size of the model at the end of/after buildClassifier, aka the classifier 
     * has been trained. 
     * 
     * The assumption, for now, is that this is measured in BYTES, but this is not enforced/ensured
     * If no memoryUsage value is supplied, the default value is -1
     */
    private long memoryUsage = -1; 
 
//REMAINDER OF THE FILE - 1 prediction per line
    //raw performance data. currently just four parallel arrays
    private ArrayList<Double> trueClassValues;
    private ArrayList<Double> predClassValues;
    private ArrayList<double[]> predDistributions;
    private ArrayList<Long> predTimes;
    private ArrayList<String> predDescriptions;
    
    //inferred/supplied dataset meta info
    private int numClasses; 
    private int numInstances;
    
    //calculated performance metrics
        //accuracy can be re-calced, as well as stored on line three in files
    public double balancedAcc; 
    public double sensitivity;
    public double specificity;
    public double precision;
    public double recall;
    public double f1; 
    public double mcc; //mathews correlation coefficient
    public double nll; 
    public double meanAUROC;
    public double stddev; //across cv folds, where applicable
    public double[][] confusionMatrix; //[actual class][predicted class]
    public double[] countPerClass;
    
    
    /**
     * Used to avoid infinite NLL scores when prob of true class =0 or 
     */
    private static double NLL_PENALTY=-6.64; //Log_2(0.01)
    
    /**
     * Consistent time unit ASSUMED across build times, test times, individual prediction times. 
     * Before considering different timeunits, all timing were in milliseconds, via
     * System.currentTimeMillis(). Some classifiers on some datasets may train/predict in less than 1 millisecond 
     * however, so as of 19/2/2019, classifierResults now defaults to working in nanoseconds. 
     * 
     * A long can contain 292 years worth of nanoseconds, which I assume to be enough for now.
     * Could be conceivable that the cumulative time of a large meta ensemble that is run 
     * multi-threaded on a large dataset might exceed this. 
     * 
     * In results files made before 19/2/2019, which only stored build times and 
     * milliseconds was assumed, there will be no unit of measurement for the time. 
     */
    private TimeUnit timeUnit = TimeUnit.NANOSECONDS;

    
    //self-management flags
    /**
     * essentially controls whether a classifierresults object can have finaliseResults(trueClassVals)
     * called upon it. In theory, every class using the classifierresults object should make new 
     * instantiations of it each time a set of results is being computed, and so this is not needed
     * 
     * this was relevant especially prior to on-line prediction storage being supported, and effectively
     * the intention was to turn the results into a const object after all the results were stored
     * 
     * todo: verify that this can be removed, or update to be more relevant. 
     */
    private boolean finalised = false;
    private boolean allStatsFound = false;
    private boolean buildTimeDuplicateWarningPrinted = false; //flag such that a warning about build times in parseThirdLine(String) is only printed once, not spammed
    
    //functional getters to retrieve info from a classifierresults object, initialised/stored here for conveniance 
    public static final Function<ClassifierResults, Double> GETTER_Accuracy = (ClassifierResults cr) -> {return cr.acc;};
    public static final Function<ClassifierResults, Double> GETTER_BalancedAccuracy = (ClassifierResults cr) -> {return cr.balancedAcc;};
    public static final Function<ClassifierResults, Double> GETTER_AUROC = (ClassifierResults cr) -> {return cr.meanAUROC;};
    public static final Function<ClassifierResults, Double> GETTER_NLL = (ClassifierResults cr) -> {return cr.nll;};
    public static final Function<ClassifierResults, Double> GETTER_F1 = (ClassifierResults cr) -> {return cr.f1;};
    public static final Function<ClassifierResults, Double> GETTER_MCC = (ClassifierResults cr) -> {return cr.mcc;};
    public static final Function<ClassifierResults, Double> GETTER_Precision = (ClassifierResults cr) -> {return cr.precision;};
    public static final Function<ClassifierResults, Double> GETTER_Recall = (ClassifierResults cr) -> {return cr.recall;};
    public static final Function<ClassifierResults, Double> GETTER_Sensitivity = (ClassifierResults cr) -> {return cr.sensitivity;};
    public static final Function<ClassifierResults, Double> GETTER_Specificity = (ClassifierResults cr) -> {return cr.specificity;};
    
    
    /*********************************
     * 
     *       CONSTRUCTORS
     * 
     */
    
    /**
     * Create an empty classifierResults object.
     * 
     * If number of classes is known when making the object, it is safer to use the constructor 
     * the takes an int representing numClasses and supply the number of classes directly. 
     * 
     * In some extreme use cases, predictions on dataset splits that a particular classifier results represents
     * may not have examples of each class that actually exists in the full dataset. If it is left 
     * to infer the number of classes, some may be missing.
     */
    public ClassifierResults() {
        trueClassValues= new ArrayList<>();
        predClassValues = new ArrayList<>();
        predDistributions = new ArrayList<>();
        predTimes = new ArrayList<>();
        predDescriptions = new ArrayList<>();
        
        finalised = false;
    }
    
    /**
     * Create an empty classifierResults object.
     * 
     * If number of classes is known when making the object, it is safer to use this constructor 
     * and supply the number of classes directly. 
     * 
     * In some extreme use cases, predictions on dataset splits that a particular classifier results represents
     * may not have examples of each class that actually exists in the full dataset. If it is left 
     * to infer the number of classes, some may be missing.
     */
    public ClassifierResults(int numClasses) {
        trueClassValues= new ArrayList<>();
        predClassValues = new ArrayList<>();
        predDistributions = new ArrayList<>();
        predTimes = new ArrayList<>();
        predDescriptions = new ArrayList<>();
        
        this.numClasses = numClasses;
        finalised = false;
    }
    
    /**
     * Load a classifierresults object from the file at the specified path
     */
    public ClassifierResults(String filePathAndName) throws FileNotFoundException {
        loadResultsFromFile(filePathAndName);
    }
    
    /**
     * Create a classifier results object with complete predictions (equivalent to addAllPredictions()). The results are 
     * FINALISED after initialisation. Meta info such as classifier name, datasetname... can still be set after construction. 
     * 
     * The descriptions array argument may be null, in which case the descriptions are stored as empty strings.
     * 
     * All other arguments are required in full, however
     */
    public ClassifierResults(double[] trueClassVals, double[] predictions, double[][] distributions, long[] predTimes, String[] descriptions) throws Exception {
        trueClassValues= new ArrayList<>();
        predClassValues = new ArrayList<>();
        predDistributions = new ArrayList<>();
        this.predTimes = new ArrayList<>();
        predDescriptions = new ArrayList<>();

        addAllPredictions(trueClassVals, predictions, distributions, predTimes, descriptions);    
        finaliseResults();
    }
    
    
    
    /***********************
     * 
     *      DATASET META INFO
     * 
     * 
     */
    
    /**
     * Will return the number of classes if it has been a) explicitly set or b) found via 
     * the size of the probability distributions attached to predictions that have been 
     * stored/loaded, otherwise this will return 0.
     */
    public int numClasses() { 
        if (numClasses <= 0)
            inferNumClasses();
        return numClasses; 
    }
    public void setNumClasses(int numClasses) { 
        this.numClasses = numClasses; 
    }
    private void inferNumClasses() {
        if (predDistributions.isEmpty())
            this.numClasses = 0;
        else
            this.numClasses = predDistributions.get(0).length;
    }
    
    public int numInstances() { 
        if (numInstances <= 0)
            inferNumInstances();
        return numInstances; 
    }
    
    private void inferNumInstances() {
        this.numInstances = predClassValues.size();
    }
    
    
    
    
    /***************************
     * 
     *   LINE 1 GETS/SETS
     *  
     *  Just basic descriptive stuff, nothing fancy goign on here
     * 
     */
    
    public String getClassifierName() { return classifierName; }
    public void setClassifierName(String classifierName) { this.classifierName = classifierName; }

    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }

    public int getFoldID() { return foldID; }
    public void setFoldID(int foldID) { this.foldID = foldID; }

    /**
     * e.g "train", "test", "validation"
     */
    public String getSplit() { return split; }
    
    /**
     * e.g "train", "test", "validation"
     */
    public void setSplit(String split) { this.split = split; }

    
    /**
     * Consistent time unit ASSUMED across build times, test times, individual prediction times. 
     * Before considering different timeunits, all timing were in milliseconds, via
     * System.currentTimeMillis(). Some classifiers on some datasets may run in less than 1 millisecond 
     * however, so as of 19/2/2019, classifierResults now defaults to working in nanoseconds. 
     * 
     * A long can contain 292 years worth of nanoseconds, which I assume to be enough for now.
     * Could be conceivable that the cumulative time of a large meta ensemble that is run 
     * multi-threaded on a large dataset might exceed this. 
     * 
     * In results files made before 19/2/2019, which only stored build times and 
     * milliseconds was assumed, there will be no unit of measurement for the time. 
     */
    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /**
     * This will NOT convert any timings already stored in this classifier results object 
     * to the new time unit. e.g if build time was had already been stored in seconds as 10, THEN
     * setTimeUnit(TimeUnit.MILLISECONDS) was called, the actual value of build time would still be 10,
     * but now assumed to mean 10 milliseconds. 
     * 
     * Consistent time unit ASSUMED across build times, test times, individual prediction times. 
     * Before considering different timeunits, all timing were in milliseconds, via
     * System.currentTimeMillis(). Some classifiers on some datasets may run in less than 1 millisecond 
     * however, so as of 19/2/2019, classifierResults now defaults to working in nanoseconds. 
     * 
     * A long can contain 292 years worth of nanoseconds, which I assume to be enough for now.
     * Could be conceivable that the cumulative time of a large meta ensemble that is run 
     * multi-threaded on a large dataset might exceed this. 
     * 
     * In results files made before 19/2/2019, which only stored build times and 
     * milliseconds was assumed, there will be no unit of measurement for the time. 
     */
    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }
    
    
    
    /**
     * This is a free-form description that can hold any info you want, with the only caveat
     * being that it cannot contain newline characters. Description could be the experiment 
     * that these results were made for, e.g "Initial Univariate Benchmarks". Entirely 
     * up to the user to process if they want to. 
     * 
     * By default, it is an empty string.
     */
    public String getDescription() { 
        return description; 
    }
    /**
     * This is a free-form description that can hold any info you want, with the only caveat
     * being that it cannot contain newline characters. Description could be the experiment 
     * that these results were made for, e.g "Initial Univariate Benchmarks". Entirely 
     * up to the user to process if they want to. 
     * 
     * By default, it is an empty string.
     */
    public void setDescription(String description) { 
        this.description = description; 
    }
    
    
    
    
    /*****************************
     * 
     *     LINE 2 GETS/SETS
     * 
     */
    
    /**
     * For now, user dependent on the formatting of this string, and really, the contents of it. 
     * It is notionally intended to contain the parameters of the classifier used to produce the 
     * attached predictions, but could also store other things as well. 
     */
    public String getParas() { return paras; }
    /**
     * For now, user dependent on the formatting of this string, and really, the contents of it. 
     * It is notionally intended to contain the parameters of the classifier used to produce the 
     * attached predictions, but could also store other things as well. 
     */
    public void setParas(String paras) { this.paras = paras; }



    /*****************************
     * 
     *     LINE 3 GETS/SETS
     * 
     */    
    
    /**
     * This setter exists purely for backwards compatibility, for classifiers that 
     * for whatever reason do not have per-instance prediction info. 
     * 
     * This might be because 
     *     a) The accuracy is gathered from some internal/weka eval process that we dont
     *          want to edit, e.g out of bag error in some forests.
     *     b) The classifier (typically implementing TrainAccuracyEstimate) does not yet 
     *          save prediction info, simply because it was written before we did that and
     *          hasnt been updated. These SHOULD be refactored over time. 
     * 
     * This method will print a suitably annoying message when first called, as a reminder
     * until the accuracy is no longer directly set
     * 
     * if you REALLY dont want this message being printed, since e.g. it messing up your own print formatting,
     * set ClassifierResults.printSetAccWarning to false.
     * 
     * Todo: remove this method, i.e. the possibility to directly set the accuracy instead of 
     * have it calculated implicitly, when possible.
     */
    public void setAcc(double acc) {
        if (printSetAccWarning && firstTimeInSetAcc) {
            System.out.println("*********");
            System.out.println("");
            System.out.println("ClassifierResults.setAcc(double acc) called, friendly reminder to refactor the code that "
                    + "made this call.");
            System.out.println("");
            System.out.println("*********");
            
            firstTimeInSetAcc = false;
        }
        
        this.acc = acc;
    }
    public static boolean printSetAccWarning = true;
    private boolean firstTimeInSetAcc = true;
            
    public double getAcc() { 
        if (acc < 0)
            calculateAcc();
        return acc; 
    }
    private void calculateAcc() {
        if (trueClassValues == null || trueClassValues.isEmpty() || trueClassValues.get(0) == -1)
            System.out.println("**getAcc():calculateAcc() no true class values suppleid yet, cannot calculate accuracy");
        
        int size = predClassValues.size();
        double correct = .0;
        for (int i = 0; i < size; i++) {
            if (predClassValues.get(i) == trueClassValues.get(i))
                correct++;
        }
        
        acc = correct / size;
    }

    public long getBuildTime() { return buildTime; }
    public long getBuildTimeInNanos() { return timeUnit.toNanos(buildTime); }
    public void setBuildTime(long buildTime) { this.buildTime = buildTime; }

    public long getTestTime() { return testTime; }
    public long getTestTimeInNanos() { return timeUnit.toNanos(testTime); }
    public void setTestTime(long testTime) { this.testTime = testTime; }

    public long getMemory() { return memoryUsage; }
    public void setMemory(long memory) {
        this.memoryUsage = memory;
    }
    
    
    /**
     * The time taken to perform some standard benchmarking operation, to allow for a (not necessarily precise) 
     * way to measure the general speed of the hardware that these results were made on, such that users 
     * analysing the results may scale the timings in this file proportional to the benchmarks to get a consistent relative scale 
     * across different results sets. 
     * 
     * It is up to the user what this benchmark operation is, and how long it is (roughly) expected to take. If no benchmark
     * time is supplied, the default value is -1
     */
    public long getBenchmarkTime() {
        return benchmarkTime;
    }

    /**
     * The time taken to perform some standard benchmarking operation, to allow for a (not necessarily precise) 
     * way to measure the general speed of the hardware that these results were made on, such that users 
     * analysing the results may scale the timings in this file proportional to the benchmarks to get a consistent relative scale 
     * across different results sets. 
     * 
     * It is up to the user what this benchmark operation is, and how long it is (roughly) expected to take. If no benchmark
     * time is supplied, the default value is -1
     */
    public void setBenchmarkTime(long benchmarkTime) {
        this.benchmarkTime = benchmarkTime;
    }
           

    /****************************
     *   
     *    PREDICTION STORAGE
     * 
     */
    /**
     * Will update the internal prediction info using the values passed. User must pass the predicted class
     * so that they may resolve ties how they want (e.g first, randomly, take modal class, etc). 
     * The standard, used in most places, would be utilities.GenericTools.indexOfMax(double[] dist)
     * 
     * The description argument may be null, however all other arguments are required in full
     * 
     * Todo future, maaaybe add enum for tie resolution to handle it here.
     *
     * The true class is missing, however can be added in one go later with the 
     * method finaliseResults(double[] trueClassVals)
     */
    public void addPrediction(double[] dist, double predictedClass, long predictionTime, String description) {
        predDistributions.add(dist);
        predClassValues.add(predictedClass);
        
        if (description == null)
            predDescriptions.add("");
        else 
            predDescriptions.add(description);
        
        //allowing 0 in case user was unaware and/or doesnt care about a classifier taking e.g
        //0 milliseconds. todo revisit at some point when time units implemented/enforced 
        if (predictionTime < 0)
            //adding a null placeholder for now. todo revisit
            predTimes.add(null);
        
            //add a zero placeholder, such that the timings can still be summed later, would mess with averages though? todo revisit    
            //predTimes.add(0L);
        else {
            predTimes.add(predictionTime);

            if (testTime == -1)
                testTime = predictionTime;
            else 
                testTime += predictionTime;
        }
        
        numInstances++;
    }
    
    /**
     * Will update the internal prediction info using the values passed. User must pass the predicted class 
     * so that they may resolve ties how they want (e.g first, randomly, take modal class, etc). 
     * The standard, used in most places, would be utilities.GenericTools.indexOfMax(double[] dist)
     * 
     * The description argument may be null, however all other arguments are required in full
     * 
     * Todo future, maaaybe add enum for tie resolution to handle it here.
     */
    public void addPrediction(double trueClassVal, double[] dist, double predictedClass, long predictionTime, String description) {        
        addPrediction(dist,predictedClass,predictionTime,description);
        trueClassValues.add(trueClassVal);
    }
    

    /**
     * Adds all the prediction info onto this classifierResults object. Does NOT finalise the results,
     * such that (e.g) predictions from multiple dataset splits can be added to the same object if wanted
     * 
     * The description argument may be null, however all other arguments are required in full
     */
    public void addAllPredictions(double[] trueClassVals, double[] predictions, double[][] distributions, long[] predTimes, String[] descriptions) throws Exception {
        assert(trueClassVals.length == predictions.length);
        assert(trueClassVals.length == distributions.length);
        assert(trueClassVals.length == predTimes.length);
        
        if (descriptions != null)
            assert(trueClassVals.length == descriptions.length);
        
        for (int i = 0; i < trueClassVals.length; i++) {
            if (descriptions == null)
                addPrediction(trueClassVals[i], distributions[i], predictions[i], predTimes[i], null);
            else 
                addPrediction(trueClassVals[i], distributions[i], predictions[i], predTimes[i], descriptions[i]);
        }
    }
    
    /**
     * Adds all the prediction info onto this classifierResults object. Does NOT finalise the results,
     * such that (e.g) predictions from multiple dataset splits can be added to the same object if wanted
     * 
     * True class values can later be supplied (ALL IN ONE GO, if working to the above example usage..) using 
     * finaliseResults(double[] testClassVals)
     * 
     * The description argument may be null, however all other arguments are required in full
     */
    public void addAllPredictions(double[] predictions, double[][] distributions, long[] predTimes, String[] descriptions ) throws Exception {
        assert(predictions.length == distributions.length);
        assert(predictions.length == predTimes.length);
        
        if (descriptions != null)
            assert(predictions.length == descriptions.length);
        
        for (int i = 0; i < predictions.length; i++) {
            if (descriptions == null)
                addPrediction(distributions[i], predictions[i], predTimes[i], "");
            else 
                addPrediction(distributions[i], predictions[i], predTimes[i], descriptions[i]);
        }
    }
        
    /**
     * Will perform some basic validation to make sure that everything is here 
     * that is expected, and compute the accuracy etc ready for file writing. 
     * 
     * Typical usage: results.finaliseResults(instances.attributeToDoubleArray(instances.classIndex()))
     */
    public void finaliseResults(double[] testClassVals) throws Exception {
        
        //todo extra verification 
        
        if (finalised) {
            System.out.println("finaliseResults(double[] testClassVals): Results already finalised, skipping re-finalisation");
            return;
        }
        
        assert(numInstances() == testClassVals.length);
        
        if (testClassVals.length != predClassValues.size())
            throw new Exception("finaliseTestResults(double[] testClassVals): Number of predictions "
                    + "made and number of true class values passed do not match");
        
        trueClassValues = new ArrayList<>();
        for(double d:testClassVals)
            trueClassValues.add(d);
        
        finaliseResults();
    }
    
    
    /**
     * Will perform some basic validation to make sure that everything is here 
     * that is expected, and compute the accuracy etc ready for file writing. 
     * 
     * You can use this method, instead of the version that takes the double[] testClassVals
     * as an argument, if you've been storing predictions via the addPrediction overload
     * that takes the true class value of each prediction.
     */
    public void finaliseResults() throws Exception {
        if (finalised) {
            printlnDebug("finaliseResults(): Results already finalised, skipping re-finalisation");
            return;
        }
        
        //todo extra verification 
        
        if (predDistributions == null || predClassValues == null ||
                predDistributions.isEmpty() || predClassValues.isEmpty())
            throw new Exception("finaliseTestResults(): no test predictions stored for this module");
        
        double correct = .0;
        for (int inst = 0; inst < predClassValues.size(); inst++)
            if (trueClassValues.get(inst).equals(predClassValues.get(inst)))
                ++correct;
        
        acc = correct/trueClassValues.size();
        
        finalised = true;
    }
    
    
    
    
    
    
    
    
    /******************************
    *
    *          RAW DATA ACCESSORS
    * 
    *     getAsList, getAsArray, and getSingleElement of the four lists describing predictions
    * 
    */
    
    public ArrayList<Double> getTrueClassVals() {
        return trueClassValues;
    }
    
    public double[] getTrueClassValsAsArray(){
        double[] d=new double[trueClassValues.size()];
        int i=0;
        for(double x:trueClassValues)
            d[i++]=x;
        return d;
    }
    
    public double getTrueClassValue(int index){
        return trueClassValues.get(index);
    }
    
    
    public ArrayList<Double> getPredClassVals(){
        return predClassValues;
    }
    
    public double[] getPredClassValsAsArray(){
        double[] d=new double[predClassValues.size()];
        int i=0;
        for(double x:predClassValues)
            d[i++]=x;
        return d;
    }
    
    public double getPredClassValue(int index){
        return predClassValues.get(index);
    }
    

    public ArrayList<double[]> getProbabilityDistributions() { 
        return predDistributions;
    }
    
    public double[][] getProbabilityDistributionsAsArray() { 
        return predDistributions.toArray(new double[][] {});
    }
    
    public double[] getProbabilityDistribution(int i){
       if(i<predDistributions.size())
            return predDistributions.get(i);
       return null;
    }
    
    
    public ArrayList<Long> getPredictionTimes() {
        return predTimes;
    }
    
    public long[] getPredictionTimesAsArray() {
        long[] l=new long[predTimes.size()];
        int i=0;
        for(long x:predTimes)
            l[i++]=x;
        return l;
    }
    
    public long getPredictionTime(int index) {
        return predTimes.get(index);
    }
    
    public long getPredictionTimeInNanos(int index) { 
        return timeUnit.toNanos(getPredictionTime(index)); 
    }
    
    public void cleanPredictionInfo() {
        predDistributions = null;
        predClassValues = null;
        trueClassValues = null;
        predTimes = null;
        predDescriptions = null;
    }
        
        
    
    
    /********************************
    *
    *     FILE READ/WRITING
    *
    */
    
    public static boolean exists(File file) {
       return file.exists() && file.length()>0;
       //todo and is valid, maybe
    }
    public static boolean exists(String path) {
        return exists(new File(path));
    }
    
    /**
     * Reads and STORES the prediction in this classifierresults object
     * returns true if the prediction described by this string was correct (i.e. truclass==predclass) 
     * 
     * INCREMENTS NUMINSTANCES
     * 
     * If numClasses is still less than 0, WILL set numclasses if distribution info is present. 
     * 
     * [true],[pred], ,[dist[0]],...,[dist[c]], ,[predTime], ,[description until end of line, may have commas in it]
     */
    private boolean instancePredictionFromString(String predLine) { 
        String[] split=predLine.split(",");

        //collect actual/predicted class
        double trueClassVal=Double.valueOf(split[0].trim());
        double predClassVal=Double.valueOf(split[1].trim());
        
        if(split.length==2) //no probabilities, no timing. VERY old files will not have them
            return true;
        
        //collect probabilities
        int distStartInd = 3; //actual, predicted, space, distStart
        double[] dist = null;
        if (numClasses < 2) {
            List<Double> distL = new ArrayList<>();
            for(int i = distStartInd; i < split.length; i++) {
                if (split[i].equals(""))
                    break; //we're at the empty-space-separator between probs and timing 
                else 
                    distL.add(Double.valueOf(split[i].trim()));
            }
                  
            numClasses = distL.size();
            assert(numClasses >= 2);
            
            dist = new double[numClasses];
            for (int i = 0; i < numClasses; i++)
                dist[i] = distL.get(i);
        }
        else {
            //we know how many classes there should be, use this as implicit
            //file verification
            dist = new double[numClasses];
            for (int i = 0; i < numClasses; i++) {
                //now need to offset by 3.
                dist[i] = Double.valueOf(split[i+distStartInd].trim());
            }
        }
        
        //collect timings
        long predTime = -1;
        int timingInd = distStartInd + (numClasses-1) + 1 + 1; //actual, predicted, space, dist, space, timing
        if (split.length > timingInd)
            predTime = Long.parseLong(split[timingInd].trim());
        
        //collect description
        String description = "";
        int descriptionInd = timingInd + 1 + 1; //actual, predicted, space, dist, space, timing, space, description
        if (split.length > descriptionInd) {
            description = split[descriptionInd];
            
            //no reason currently why the description passed cannot have commas in it, 
            //might be a natural way to separate it in to different parts.
            //description reall just fills up the remainder of the line.
            for (int i = descriptionInd+1; i < split.length; i++)
                description += "," + split[i];
        }
            
        
        addPrediction(trueClassVal, dist, predClassVal, predTime, description);
        return trueClassVal==predClassVal;
    }
    
    
    /**
     * [true],[pred], ,[dist[0]],...,[dist[c]], ,[predTime], ,[description until end of line, may have commas in it]
     */
    private String instancePredictionToString(int i) { 
        StringBuilder sb = new StringBuilder();
        
        sb.append(trueClassValues.get(i).intValue()).append(",");
        sb.append(predClassValues.get(i).intValue());
        
        //probs
        sb.append(","); //<empty space>
        double[] probs=predDistributions.get(i);
        for(double d:probs)
            sb.append(",").append(GenericTools.RESULTS_DECIMAL_FORMAT.format(d));
        
        //timing 
        sb.append(",,").append(predTimes.get(i)); //<empty space>, timing
        
        //description 
        sb.append(",,").append(predDescriptions.get(i)); //<empty space>, description
        
        return sb.toString();
    }
    
    public String instancePredictionsToString() throws Exception{
        
        //todo extra verification 
        
        if (trueClassValues == null || trueClassValues.size() == 0 || trueClassValues.get(0) == -1)
            throw new Exception("No true class value stored, call finaliseResults(double[] trueClassVal)");
        
        if(numInstances()>0 &&(predDistributions.size()==trueClassValues.size()&& predDistributions.size()==predClassValues.size())){
            StringBuilder sb=new StringBuilder("");
            
            for(int i=0;i<numInstances();i++){
                sb.append(instancePredictionToString(i));

                if(i<numInstances()-1)
                    sb.append("\n");
            }
            
            return sb.toString();
        }
        else 
           return "No Instance Prediction Information";
    }
   
    @Override
    public String toString() {                
        return generateFirstLine();
    }
    
    public String writeResultsFileToString() throws Exception {         
        finaliseResults();
        
        StringBuilder st = new StringBuilder();
        st.append(generateFirstLine()).append("\n");
        st.append(generateSecondLine()).append("\n");
        st.append(generateThirdLine()).append("\n");

        st.append(instancePredictionsToString());
        return st.toString();
    }
   
    public void writeResultsToFile(String path) throws Exception {
        OutFile out = null;
        try {
            out = new OutFile(path);
            out.writeString(writeResultsFileToString());
        } catch (Exception e) { 
             throw new Exception("Error writing results file.\n"
                     + "Outfile most likely didnt open successfully, probably directory doesnt exist yet.\n" 
                     + "Path: " + path +"\nError: "+ e);
        } finally {
            out.closeFile();
        }
    }
    
    private void parseFirstLine(String line) {
        String[] parts = line.split(",");
        if (parts.length == 0)
            return;
        
        //old tuned classifiers (and maybe others) just wrote a classifier name identifier
        //covering for backward compatability, otherwise datasetname is first
        if (parts.length == 1)
            classifierName = parts[0];
        else {
            datasetName = parts[0];
            classifierName = parts[1];
        }
        
        if (parts.length > 2)
            split = parts[2];
        
        if (parts.length > 3)
            foldID = Integer.parseInt(parts[3]);
        
        if (parts.length > 4)
            setTimeUnitFromString(parts[4]);
        else //time unit is missing, assumed to be older file, which recorded build times in milliseconds by default
            timeUnit = TimeUnit.MILLISECONDS; 
        
        if (parts.length > 5)
            description = parts[5];

        //nothing stopping the description from having its own commas in it, jsut read until end of line
        for (int i = 6; i < parts.length; i++)
            description += "," + parts[i];
    }
    private String generateFirstLine() { 
        return datasetName + "," + classifierName + "," + split + "," + foldID + "," + getTimeUnitAsString() + ","+ description;
    }
   
    private void parseSecondLine(String line) { 
        paras = line;
       
        //handle buildtime if it's on this line like older files may have, 
        //taking it out of the generic paras string and putting the value into the actual field
        String[] parts = paras.split(",");
        if (parts.length > 0 && parts[0].contains("BuildTime")) {
            buildTime = (long)Double.parseDouble(parts[1].trim());
            
            if (parts.length > 2) { //this has actual paras too, rebuild this string without buildtime
                paras = parts[2];
                for (int i = 3; i < parts.length; i++) {
                    paras += "," + parts[i];
                }
            }
        }
    }
    private String generateSecondLine() {
        //todo decide what to do with this
        return paras;
    }
    
    /**
     * Returns the test acc reported on this line, for comparison with acc 
     * computed later to assert they align. Accuracy has always been reported 
     * on this line in this file format, so fair to assume if this fails 
     * then the file is simply malformed
     */
    private double parseThirdLine(String line) { 
        String[] parts = line.split(",");
        
        acc = Double.parseDouble(parts[0]);
       
        //if buildtime is here, it shouldn't be on the paras line too. 
        //if it is, likely an old SaveParameterInfo implementation put it there
        //for now, overwriting that buildtime with this one, but printing warning 
        if (parts.length > 1)  {
            if (buildTime != -1 && !buildTimeDuplicateWarningPrinted)  {
                System.out.println("CLASSIFIERRESULTS READ WARNING: build time reported on both "
                        + "second and third line. Using the value reported on the third line");

                buildTimeDuplicateWarningPrinted = true;
            }
            
            buildTime = Long.parseLong(parts[1]); 
        }
        if (parts.length > 2) 
            testTime = Long.parseLong(parts[2]);
        if (parts.length > 3) 
            benchmarkTime = Long.parseLong(parts[3]);
        if (parts.length > 4) 
            memoryUsage = Long.parseLong(parts[4]);
        
        return acc;
    }
    private String generateThirdLine() {
        String res = acc
            + "," + buildTime
            + "," + testTime
            + "," + benchmarkTime
            + "," + memoryUsage;
        return res;
    }

    private String getTimeUnitAsString() { 
        return timeUnit.name();
    }
    
    private void setTimeUnitFromString(String str) {
        timeUnit = TimeUnit.valueOf(str);
    }
    
    public void loadResultsFromFile(String path) throws FileNotFoundException {
        //init
        trueClassValues = new ArrayList<>();
        predClassValues = new ArrayList<>();
        predDistributions = new ArrayList<>();
        predTimes = new ArrayList<>();
        predDescriptions = new ArrayList<>();
        numInstances = 0;
        acc = -1;
        buildTime = -1;
        testTime = -1; 
        memoryUsage = -1;

        //check file exists
        File f = new File(path);
        if (!(f.exists() && f.length() > 0)) 
            throw new FileNotFoundException("File " + path + " NOT FOUND");

        Scanner inf = new Scanner(f);

        //parse meta infos
        parseFirstLine(inf.nextLine());
        parseSecondLine(inf.nextLine());
        double reportedTestAcc = parseThirdLine(inf.nextLine());

        //have all meta info, start reading predictions
        double correct = 0;
        while (inf.hasNext()) {
            String line = inf.nextLine();
            //may be trailing empty lines at the end of the file
            if (line == null || line.equals(""))
                break;
            
            if (instancePredictionFromString(line))
                correct++;
        }

        //acts as a basic form of verification
        acc = correct / numInstances;
        double eps = 1.e-8;
        if (Math.abs(reportedTestAcc - acc) > eps) {
            throw new ArithmeticException("Calculated accuracy (" + acc + ") differs from written accuracy (" + reportedTestAcc + ") "
                    + "by more than eps (" + eps + ")");
        }

        finalised = true;
        inf.close();
    }
   
    
    
    
    
    
    
    
    
    
    /******************************************
     * 
     *   METRIC CALCULATIONS 
     *
     */
    
    
    
    /**
     * Find: Accuracy, Balanced Accuracy, F1 (1 vs All averaged?), 
     * Sensitivity, Specificity, AUROC, negative log likelihood, MCC
     */   
    public void findAllStats(){
       if (numInstances <= 0)
           inferNumInstances();
        
       confusionMatrix=buildConfusionMatrix();
       
       countPerClass=new double[confusionMatrix.length];
       for(int i=0;i<trueClassValues.size();i++)
           countPerClass[trueClassValues.get(i).intValue()]++;
       
       acc=0;
       for(int i=0;i<numClasses;i++)
           acc+=confusionMatrix[i][i];
       acc/=numInstances;
       
       balancedAcc=findBalancedAcc(confusionMatrix);
       nll=findNLL();
       meanAUROC=findMeanAUROC();
       mcc = computeMCC(confusionMatrix);
       
       f1=findF1(confusionMatrix); //also handles spec/sens/prec/recall in the process of finding f1
       
       allStatsFound = true;
    }
   
    public void findAllStatsOnce(){
        if (finalised && allStatsFound) {
            System.out.println("Stats already found, ignoring findAllStatsOnce()");
            return;
        } 
        else {
            findAllStats();
        }
    }
      
       
    /**
    * @return [actual class][predicted class]
    */
    private double[][] buildConfusionMatrix() {
        double[][] matrix = new double[numClasses][numClasses];
        for (int i = 0; i < predClassValues.size(); ++i){
            double actual=trueClassValues.get(i);
            double predicted=predClassValues.get(i);
            ++matrix[(int)actual][(int)predicted];
        }
        return matrix;
    }
    
    
    /**
     * uses only the probability of the true class
     */
    public double findNLL(){
        double nll=0;
        for(int i=0;i<trueClassValues.size();i++){
            double[] dist=getProbabilityDistribution(i);
            int trueClass = trueClassValues.get(i).intValue();
            
            if(dist[trueClass]==0)
                nll+=NLL_PENALTY;
            else
                nll+=Math.log(dist[trueClass])/Math.log(2);//Log 2
        }
        return -nll/trueClassValues.size();
    }
           
    public double findMeanAUROC(){
        double a=0;
        if(numClasses==2){
            a=findAUROC(1);
/*            if(countPerClass[0]<countPerClass[1])
            else
                a=findAUROC(1);
 */       }
        else{
            double[] classDist = InstanceTools.findClassDistributions(trueClassValues, numClasses);
            for(int i=0;i<numClasses;i++){
                a+=findAUROC(i) * classDist[i];
            }
            
            //original, unweighted
//            for(int i=0;i<numClasses;i++){
//                a+=findAUROC(i);
//            }
//            a/=numClasses;
        }
        return a;
    }
   
    /**
     * todo could easily be optimised further if really wanted
     */
    public double computeMCC(double[][] confusionMatrix) {
        
        double num=0.0;
        for (int k = 0; k < confusionMatrix.length; ++k)
            for (int l = 0; l < confusionMatrix.length; ++l)
                for (int m = 0; m < confusionMatrix.length; ++m) 
                    num += (confusionMatrix[k][k]*confusionMatrix[m][l])-
                            (confusionMatrix[l][k]*confusionMatrix[k][m]);

        if (num == 0.0)
            return 0;
        
        double den1 = 0.0; 
        double den2 = 0.0;
        for (int k = 0; k < confusionMatrix.length; ++k) {
            
            double den1Part1=0.0;
            double den2Part1=0.0;
            for (int l = 0; l < confusionMatrix.length; ++l) {
                den1Part1 += confusionMatrix[l][k];
                den2Part1 += confusionMatrix[k][l];
            }

            double den1Part2=0.0;
            double den2Part2=0.0;
            for (int kp = 0; kp < confusionMatrix.length; ++kp)
                if (kp!=k) {
                    for (int lp = 0; lp < confusionMatrix.length; ++lp) {
                        den1Part2 += confusionMatrix[lp][kp];
                        den2Part2 += confusionMatrix[kp][lp];
                    }
                }
                      
            den1 += den1Part1 * den1Part2;
            den2 += den2Part1 * den2Part2;
        }
        
        return num / (Math.sqrt(den1)*Math.sqrt(den2));
    }
   
    /**
     * Balanced accuracy: average of the accuracy for each class
     * @param cm
     * @return 
     */   
    public double findBalancedAcc(double[][] cm){
        double[] accPerClass=new double[cm.length];
        for(int i=0;i<cm.length;i++)
            accPerClass[i]=cm[i][i]/countPerClass[i];
        double b=accPerClass[0];
        for(int i=1;i<cm.length;i++)
            b+=accPerClass[i]; 
        b/=cm.length;
        return b;
    }
    
    /**
     * F1: If it is a two class problem we use the minority class
     * if it is multiclass we average over all classes. 
     * @param cm
     * @return 
     */   
    public double findF1(double[][] cm){
        double f=0;
        if(numClasses==2){
            if(countPerClass[0]<countPerClass[1])
                f=findConfusionMatrixMetrics(cm,0,1);
            else
                f=findConfusionMatrixMetrics(cm,1,1);
        }
        else{//Average over all of them
            for(int i=0;i<numClasses;i++)
                f+=findConfusionMatrixMetrics(cm,i,1);
            f/=numClasses;
        }
        return f;
    }
   
    protected double findConfusionMatrixMetrics(double[][] confMat, int c,double beta) {
        double tp = confMat[c][c]; //[actual class][predicted class]
        //some very small non-zero value, in the extreme case that no cases of 
        //this class were correctly classified
        if (tp == .0)
            return .0000001; 
        
        double fp = 0.0, fn = 0.0,tn=0.0;
        
        for (int i = 0; i < confMat.length; i++) {
            if (i!=c) {
                fp += confMat[i][c];
                fn += confMat[c][i];
                tn+=confMat[i][i];
            }
        }
         
        precision = tp / (tp+fp);
        recall = tp / (tp+fn);
        sensitivity=recall;
        specificity=tn/(fp+tn);
        
        //jamesl
        //one in a million case on very small AND unbalanced datasets (lenses...) that particular train/test splits and their cv splits
        //lead to a divide by zero on one of these stats (C4.5, lenses, trainFold7 (and a couple others), specificity in the case i ran into)
        //as a little work around, if this case pops up, will simply set the stat to 0
        if (Double.compare(precision, Double.NaN) == 0)
            precision = 0;
        if (Double.compare(recall, Double.NaN) == 0)
            recall = 0;
        if (Double.compare(sensitivity, Double.NaN) == 0)
            sensitivity = 0;
        if (Double.compare(specificity, Double.NaN) == 0)
            specificity = 0;
        
        return (1+beta*beta) * (precision*recall) / ((beta*beta)*precision + recall);
    }
    
    protected double findAUROC(int c){
        class Pair implements Comparable<Pair>{
            Double x;
            Double y;
            public Pair(Double a, Double b){
                x=a;
                y=b;
            }
            @Override
            public int compareTo(Pair p) {
                return p.x.compareTo(x);
            }
            public String toString(){ return "("+x+","+y+")";}
        }
        
        ArrayList<Pair> p=new ArrayList<>();
        double nosPositive=0,nosNegative;
        for(int i=0;i<numInstances;i++){
            Pair temp=new Pair(predDistributions.get(i)[c],trueClassValues.get(i));
            if(c==trueClassValues.get(i))
                nosPositive++;
            p.add(temp);
        }
        nosNegative=trueClassValues.size()-nosPositive;
        Collections.sort(p);
        
        /* http://www.cs.waikato.ac.nz/~remco/roc.pdf
                Determine points on ROC curve as follows; 
                starts in the origin and goes one unit up, for every
        negative outcome the curve goes one unit to the right. Units on the x-axis
        are 1
        #TN and on the y-axis 1
        #TP where #TP (#TN) is the total number
        of true positives (true negatives). This gives the points on the ROC curve
        (0; 0); (x1; y1); : : : ; (xn; yn); (1; 1).
        */
        ArrayList<Pair> roc=new ArrayList<>();
        double x=0;
        double oldX=0;
        double y=0;
        int xAdd=0, yAdd=0;
        boolean xLast=false,yLast=false;
        roc.add(new Pair(x,y));
        for(int i=0;i<numInstances;i++){
            if(p.get(i).y==c){
                if(yLast)
                    roc.add(new Pair(x,y));
                xLast=true;
                yLast=false;
                x+=1/nosPositive;
                xAdd++;
                if(xAdd==nosPositive)
                    x=1.0;
                
            }
            else{ 
                if(xLast)
                    roc.add(new Pair(x,y));
                yLast=true;
                xLast=false;
                y+=1/nosNegative;
                yAdd++;
                if(yAdd==nosNegative)
                    y=1.0;
            }
        }
        roc.add(new Pair(1.0,1.0));
        
        //Calculate the area under the ROC curve, as the sum over all trapezoids with
        //base xi+1 to xi , that is, A

        double auroc=0;
        for(int i=0;i<roc.size()-1;i++){
            auroc+=(roc.get(i+1).y-roc.get(i).y)*(roc.get(i+1).x);
        }
        return auroc;
    } 
    
    public String allPerformanceMetricsToString(){
        String str="Acc,"+acc+"\n";
        str+="BalancedAcc,"+balancedAcc+"\n"; 
        str+="sensitivity,"+sensitivity+"\n"; 
        str+="precision,"+precision+"\n"; 
        str+="recall,"+recall+"\n"; 
        str+="specificity,"+specificity+"\n";         
        str+="f1,"+f1+"\n"; 
        str+="mcc,"+mcc+"\n"; 
        str+="nll,"+nll+"\n"; 
        str+="meanAUROC,"+meanAUROC+"\n"; 
        str+="stddev,"+stddev+"\n"; 
        str+="Count per class:\n";
        for(int i=0;i<countPerClass.length;i++)
            str+="Class "+i+","+countPerClass[i]+"\n";
        str+="Confusion Matrix:\n";
        for(int i=0;i<confusionMatrix.length;i++){
            for(int j=0;j<confusionMatrix[i].length;j++)
                str+=confusionMatrix[i][j]+",";
            str+="\n";
        }
        return str;
    }

    public static ArrayList<Pair<String, Function<ClassifierResults, Double>>> getDefaultStatistics() { 
        ArrayList<Pair<String, Function<ClassifierResults, Double>>> stats = new ArrayList<>();
        stats.add(new Pair<>("ACC", GETTER_Accuracy));
        stats.add(new Pair<>("BALACC", GETTER_BalancedAccuracy));
        stats.add(new Pair<>("AUROC", GETTER_AUROC));
        stats.add(new Pair<>("NLL", GETTER_NLL));
        return stats;
    }
        
    public static ArrayList<Pair<String, Function<ClassifierResults, Double>>> getAllStatistics() { 
        ArrayList<Pair<String, Function<ClassifierResults, Double>>> stats = new ArrayList<>();
        stats.add(new Pair<>("ACC", GETTER_Accuracy));
        stats.add(new Pair<>("BALACC", GETTER_BalancedAccuracy));
        stats.add(new Pair<>("AUROC", GETTER_AUROC));
        stats.add(new Pair<>("NLL", GETTER_NLL));
        stats.add(new Pair<>("F1", GETTER_F1));
        stats.add(new Pair<>("MCC", GETTER_MCC));
        stats.add(new Pair<>("Prec", GETTER_Precision));
        stats.add(new Pair<>("Recall", GETTER_Recall));
        stats.add(new Pair<>("Sens", GETTER_Sensitivity));
        stats.add(new Pair<>("Spec", GETTER_Specificity));
        return stats;
    }
    
    public static ArrayList<Pair<String, Function<ClassifierResults, Double>>> getAccuracyStatistic() { 
        ArrayList<Pair<String, Function<ClassifierResults, Double>>> stats = new ArrayList<>();
        stats.add(new Pair<>("ACC", GETTER_Accuracy));
        return stats;
    }
    
    public static void main(String[] args) throws Exception {
        readWriteTest();
    }
    
    private static void readWriteTest() throws Exception { 
        ClassifierResults res = new ClassifierResults();
        
        res.setClassifierName("testClassifier");
        res.setDatasetName("testDataset");
        //empty split
        //empty foldid
        res.setDescription("boop, guest");
        
        res.setParas("test,west,best");
        
        //acc handled internally
        res.setBuildTime(2);
        res.setTestTime(1);
        //empty benchmark
        //empty memory
        
        Random rng = new Random(0);
        for (int i = 0; i < 10; i++) { //obvs dists dont make much sense, not important here
            res.addPrediction(rng.nextInt(2), new double[] { rng.nextDouble(), rng.nextDouble()}, rng.nextInt(2), rng.nextInt(5), "test,again");
        }
        
        res.finaliseResults();
        
        System.out.println(res.writeResultsFileToString());
        System.out.println("\n\n");
        
        res.writeResultsToFile("test.csv");
        
        ClassifierResults res2 = new ClassifierResults("test.csv");
        System.out.println(res2.writeResultsFileToString());
    }
}
