package org.deeplearning4j.datasets.canova;

import org.canova.api.records.reader.SequenceRecordReader;
import org.canova.api.writable.Writable;
import org.deeplearning4j.datasets.iterator.DataSetIterator;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.util.FeatureUtil;

import java.util.*;

/**
 * Sequence record reader data set iterator
 * Given a record reader (and optionally another record reader for the labels) generate time series (sequence) data sets.<br>
 * Supports padding for one-to-many and many-to-one type data loading (i.e., with different number of inputs vs.
 * labels via the {@link org.deeplearning4j.datasets.canova.SequenceRecordReaderDataSetIterator.AlignmentMode} mode.
 *
 */
public class SequenceRecordReaderDataSetIterator implements DataSetIterator {
    /**Alignment mode for dealing with input/labels of differing lengths (for example, one-to-many and many-to-one type situations).
     * For example, might have 10 time steps total but only one label at end for sequence classification.<br>
     * Currently supported modes:<br>
     * <b>EQUAL_LENGTH</b>: Default. Assume that label and input time series are of equal length, and all examples are of
     * the same length<br>
     * <b>ALIGN_START</b>: Align the label/input time series at the first time step, and zero pad either the labels or
     * the input at the end<br>
     * <b>ALIGN_END</b>: Align the label/input at the last time step, zero padding either the input or the labels as required<br>
     *
     * Note 1: When the time series for each example are of different lengths, the shorter time series will be padded to
     * the length of the longest time series.<br>
     * Note 2: When ALIGN_START or ALIGN_END are used, the DataSet masking functionality is used. Thus, the returned DataSets
     * will have the input and mask arrays set. These mask arrays identify whether an input/label is actually present,
     * or whether the value is merely masked.<br>
     */
    public enum AlignmentMode {
        EQUAL_LENGTH,
        ALIGN_START,
        ALIGN_END
    }
    private SequenceRecordReader recordReader;
    private SequenceRecordReader labelsReader;
    private int miniBatchSize = 10;
    private final boolean regression;
    private int labelIndex = -1;
    private final int numPossibleLabels;
    private int cursor = 0;
    private int inputColumns = -1;
    private int totalOutcomes = -1;
    private boolean useStored = false;
    private DataSet stored = null;
    private DataSetPreProcessor preProcessor;
    private AlignmentMode alignmentMode;

    private final boolean singleSequenceReaderMode;

    /**
     * Constructor where features and labels come from different RecordReaders (for example, different files)
     */
    public SequenceRecordReaderDataSetIterator(SequenceRecordReader featuresReader, SequenceRecordReader labels,
                                               int miniBatchSize, int numPossibleLabels, boolean regression) {
        this(featuresReader,labels,miniBatchSize,numPossibleLabels,regression,AlignmentMode.EQUAL_LENGTH);
    }

    /**
     * Constructor where features and labels come from different RecordReaders (for example, different files)
     */
    public SequenceRecordReaderDataSetIterator(SequenceRecordReader featuresReader, SequenceRecordReader labels,
                                               int miniBatchSize, int numPossibleLabels, boolean regression,
                                               AlignmentMode alignmentMode) {
        this.recordReader = featuresReader;
        this.labelsReader = labels;
        this.miniBatchSize = miniBatchSize;
        this.numPossibleLabels = numPossibleLabels;
        this.regression = regression;
        this.alignmentMode = alignmentMode;
        this.singleSequenceReaderMode = false;
    }

    /** Constructor where features and labels come from the SAME RecordReader (i.e., target/label is a column in the
     * same data as the features)
     * @param reader SequenceRecordReader with data
     * @param miniBatchSize size of each minibatch
     * @param numPossibleLabels number of labels/classes for classification (or not used if regression == true)
     * @param labelIndex index in input of the label index
     * @param regression Whether output is for regression or classification
     */
    public SequenceRecordReaderDataSetIterator(SequenceRecordReader reader, int miniBatchSize, int numPossibleLabels,
                                               int labelIndex, boolean regression){
        this.recordReader = reader;
        this.labelsReader = null;
        this.miniBatchSize = miniBatchSize;
        this.regression = regression;
        this.labelIndex = labelIndex;
        this.numPossibleLabels = numPossibleLabels;
        this.singleSequenceReaderMode = true;
    }

    @Override
    public boolean hasNext() {
        return recordReader.hasNext();
    }

    @Override
    public DataSet next() {
        return next(miniBatchSize);
    }


    @Override
    public DataSet next(int num) {
        if (useStored) {
            useStored = false;
            DataSet temp = stored;
            stored = null;
            if (preProcessor != null) preProcessor.preProcess(temp);
            return temp;
        }
        if (!hasNext()) throw new NoSuchElementException();

        if (singleSequenceReaderMode) {
            return nextSingleSequenceReader(num);
        } else {
            return nextMultipleSequenceReaders(num);
        }
    }

    private DataSet nextSingleSequenceReader(int num){
        List<INDArray> listFeatures = new ArrayList<>(num);
        List<INDArray> listLabels = new ArrayList<>(num);
        int minLength = 0;
        int maxLength = 0;
        for( int i=0; i<num && hasNext(); i++ ){
            Collection<Collection<Writable>> sequence = recordReader.sequenceRecord();
            INDArray[] fl = getFeaturesLabelsSingleReader(sequence);
            if(i == 0){
                minLength = fl[0].size(0);
                maxLength = minLength;
            } else {
                minLength = Math.min(minLength,fl[0].size(0));
                maxLength = Math.max(maxLength,fl[0].size(0));
            }
            listFeatures.add(fl[0]);
            listLabels.add(fl[1]);
        }

        //Convert to 3d minibatch:
        INDArray featuresOut = Nd4j.create(listFeatures.size(),listFeatures.get(0).size(1),maxLength);
        INDArray labelsOut = Nd4j.create(listFeatures.size(),(regression ? 1 : numPossibleLabels),maxLength);
        INDArray featuresMask = null;
        INDArray labelsMask = null;

        if(minLength == maxLength){
            for (int i = 0; i < listFeatures.size(); i++) {
                featuresOut.tensorAlongDimension(i, 1, 2).assign(listFeatures.get(i));
                labelsOut.tensorAlongDimension(i, 1, 2).assign(listLabels.get(i));
            }
        } else {
            featuresMask = Nd4j.ones(listFeatures.size(),maxLength);
            labelsMask = Nd4j.ones(listLabels.size(),maxLength);
            for (int i = 0; i < listFeatures.size(); i++) {
                INDArray f = listFeatures.get(i);
                int tsLength = f.size(0);

                featuresOut.tensorAlongDimension(i, 1, 2).put(new INDArrayIndex[]{NDArrayIndex.interval(0, tsLength), NDArrayIndex.all()}, f);
                labelsOut.tensorAlongDimension(i, 1, 2).put(new INDArrayIndex[]{NDArrayIndex.interval(0, tsLength), NDArrayIndex.all()}, listLabels.get(i));
                for( int j=tsLength; j<maxLength; j++ ){
                    featuresMask.put(i,j,0.0);
                    labelsMask.put(i,j,0.0);
                }
            }
        }

        cursor += listFeatures.size();
        if (inputColumns == -1) inputColumns = featuresOut.size(1);
        if (totalOutcomes == -1) totalOutcomes = labelsOut.size(1);
        DataSet ds = new DataSet(featuresOut, labelsOut, featuresMask, labelsMask);
        if (preProcessor != null) preProcessor.preProcess(ds);
        return ds;
    }

    private DataSet nextMultipleSequenceReaders(int num){
        List<INDArray> featureList = new ArrayList<>(num);
        List<INDArray> labelList = new ArrayList<>(num);
        for (int i = 0; i < num && hasNext(); i++) {

            Collection<Collection<Writable>> featureSequence = recordReader.sequenceRecord();
            Collection<Collection<Writable>> labelSequence = labelsReader.sequenceRecord();

            INDArray features = getFeatures(featureSequence);
            INDArray labels = getLabels(labelSequence); //2d time series, with shape [timeSeriesLength,vectorSize]

            featureList.add(features);
            labelList.add(labels);
        }

        //Convert 2d sequences/time series to 3d minibatch data
        INDArray featuresOut;
        INDArray labelsOut;
        INDArray featuresMask = null;
        INDArray labelsMask = null;
        if(alignmentMode == AlignmentMode.EQUAL_LENGTH) {
            int[] featureShape = new int[3];
            featureShape[0] = featureList.size();   //mini batch size
            featureShape[1] = featureList.get(0).size(1);   //example vector size
            featureShape[2] = featureList.get(0).size(0);   //time series/sequence length

            int[] labelShape = new int[3];
            labelShape[0] = labelList.size();
            labelShape[1] = labelList.get(0).size(1);   //label vector size
            labelShape[2] = labelList.get(0).size(0);   //time series/sequence length

            featuresOut = Nd4j.create(featureShape);
            labelsOut = Nd4j.create(labelShape);
            for (int i = 0; i < featureList.size(); i++) {
                featuresOut.tensorAlongDimension(i, 1, 2).assign(featureList.get(i));
                labelsOut.tensorAlongDimension(i, 1, 2).assign(labelList.get(i));
            }
        } else if( alignmentMode == AlignmentMode.ALIGN_START ){
            int longestTimeSeries = 0;
            for(INDArray features : featureList){
                longestTimeSeries = Math.max(features.size(0),longestTimeSeries);
            }
            for(INDArray labels : labelList ){
                longestTimeSeries = Math.max(labels.size(0),longestTimeSeries);
            }

            int[] featuresShape = new int[]{
                    featureList.size(), //# examples
                    featureList.get(0).size(1), //example vector size
                    longestTimeSeries};
            int[] labelsShape = new int[]{
                    labelList.size(), //# examples
                    labelList.get(0).size(1), //example vector size
                    longestTimeSeries};

            featuresOut = Nd4j.create(featuresShape);
            labelsOut = Nd4j.create(labelsShape);
            featuresMask = Nd4j.ones(featureList.size(),longestTimeSeries);
            labelsMask = Nd4j.ones(labelList.size(),longestTimeSeries);
            int[] temp = new int[2];
            for (int i = 0; i < featureList.size(); i++) {
                INDArray f = featureList.get(i);
                INDArray l = labelList.get(i);

                featuresOut.tensorAlongDimension(i, 1, 2)
                        .put(new INDArrayIndex[]{NDArrayIndex.interval(0, f.size(0)), NDArrayIndex.all()}, f);
                labelsOut.tensorAlongDimension(i, 1, 2)
                        .put(new INDArrayIndex[]{NDArrayIndex.interval(0, l.size(0)), NDArrayIndex.all()}, l);
                temp[0] = i;
                for( int j=f.size(0); j<longestTimeSeries; j++ ){
                    temp[1] = j;
                    featuresMask.putScalar(temp,0.0);
                }
                for( int j=l.size(0); j<longestTimeSeries; j++ ){
                    temp[1] = j;
                    labelsMask.putScalar(temp,0.0);
                }
            }
        } else if( alignmentMode == AlignmentMode.ALIGN_END ){    //Align at end

            int longestTimeSeries = 0;
            for(INDArray features : featureList){
                longestTimeSeries = Math.max(features.size(0),longestTimeSeries);
            }
            for(INDArray labels : labelList ){
                longestTimeSeries = Math.max(labels.size(0),longestTimeSeries);
            }

            int[] featuresShape = new int[]{
                    featureList.size(), //# examples
                    featureList.get(0).size(1), //example vector size
                    longestTimeSeries};
            int[] labelsShape = new int[]{
                    labelList.size(), //# examples
                    labelList.get(0).size(1), //example vector size
                    longestTimeSeries};

            featuresOut = Nd4j.create(featuresShape);
            labelsOut = Nd4j.create(labelsShape);
            featuresMask = Nd4j.ones(featureList.size(), longestTimeSeries);
            labelsMask = Nd4j.ones(labelList.size(), longestTimeSeries);
            int[] temp = new int[2];
            for (int i = 0; i < featureList.size(); i++) {
                INDArray f = featureList.get(i);
                INDArray l = labelList.get(i);

                int fLen = f.size(0);
                int lLen = l.size(0);
                temp[0] = i;

                if(fLen >= lLen){
                    //Align labels with end of features (features are longer)
                    featuresOut.tensorAlongDimension(i, 1, 2)
                            .put(new INDArrayIndex[]{NDArrayIndex.interval(0, fLen), NDArrayIndex.all()}, f);
                    labelsOut.tensorAlongDimension(i, 1, 2)
                            .put(new INDArrayIndex[]{NDArrayIndex.interval(fLen-lLen, fLen), NDArrayIndex.all()}, l);

                    for( int j=fLen; j<longestTimeSeries; j++ ){
                        temp[1] = j;
                        featuresMask.putScalar(temp,0.0);
                    }
                    //labels mask: component before labels
                    for( int j=0; j<fLen-lLen; j++ ){
                        temp[1] = j;
                        labelsMask.putScalar(temp,0.0);
                    }
                    //labels mask: component after labels
                    for( int j=fLen; j<longestTimeSeries; j++ ){
                        temp[1] = j;
                        labelsMask.putScalar(temp,0.0);
                    }
                } else {
                    //Align features with end of labels (labels are longer)
                    featuresOut.tensorAlongDimension(i, 1, 2)
                            .put(new INDArrayIndex[]{NDArrayIndex.interval(lLen-fLen, lLen), NDArrayIndex.all()}, f);
                    labelsOut.tensorAlongDimension(i, 1, 2)
                            .put(new INDArrayIndex[]{NDArrayIndex.interval(0, lLen), NDArrayIndex.all()}, l);

                    //features mask: component before features
                    for( int j=0; j<lLen-fLen; j++ ){
                        temp[1] = j;
                        featuresMask.putScalar(temp,0.0);
                    }
                    //features mask: component after features
                    for( int j=lLen; j<longestTimeSeries; j++ ){
                        temp[1] = j;
                        featuresMask.putScalar(temp,0.0);
                    }

                    //labels mask
                    for( int j=lLen; j<longestTimeSeries; j++ ){
                        temp[1] = j;
                        labelsMask.putScalar(temp,0.0);
                    }
                }
            }

        } else {
            throw new UnsupportedOperationException("Unknown alignment mode: " + alignmentMode);
        }

        cursor += featureList.size();
        if (inputColumns == -1) inputColumns = featuresOut.size(1);
        if (totalOutcomes == -1) totalOutcomes = labelsOut.size(1);
        DataSet ds = new DataSet(featuresOut, labelsOut, featuresMask, labelsMask);
        if (preProcessor != null) preProcessor.preProcess(ds);
        return ds;
    }

    @Override
    public int totalExamples() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int inputColumns() {
        if (inputColumns != -1) return inputColumns;
        preLoad();
        return inputColumns;
    }

    @Override
    public int totalOutcomes() {
        if (totalOutcomes != -1) return totalOutcomes;
        preLoad();
        return totalOutcomes;
    }

    private void preLoad() {
        stored = next();
        useStored = true;
        inputColumns = stored.getFeatureMatrix().size(1);
        totalOutcomes = stored.getLabels().size(1);
    }

    @Override
    public void reset() {
        recordReader.reset();
        if(labelsReader != null) labelsReader.reset();  //May be null for single seqRR case
        cursor = 0;
        stored = null;
        useStored = false;
    }

    @Override
    public int batch() {
        return miniBatchSize;
    }

    @Override
    public int cursor() {
        return cursor;
    }

    @Override
    public int numExamples() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void setPreProcessor(DataSetPreProcessor preProcessor) {
        this.preProcessor = preProcessor;
    }

    @Override
    public List<String> getLabels() {
        return null;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Remove not supported for this iterator");
    }

    private INDArray getFeatures(Collection<Collection<Writable>> features) {

        //Size of the record?
        int[] shape = new int[2]; //[timeSeriesLength,vectorSize]
        shape[0] = features.size();

        Iterator<Collection<Writable>> iter = features.iterator();

        int i = 0;
        INDArray out = null;
        while (iter.hasNext()) {
            Collection<Writable> step = iter.next();
            if (i == 0) {
                shape[1] = step.size();
                out = Nd4j.create(shape);
            }

            Iterator<Writable> timeStepIter = step.iterator();
            int f = 0;
            while (timeStepIter.hasNext()) {
                Writable current = timeStepIter.next();
                out.put(i, f++, current.toDouble());
            }
            i++;
        }
        return out;
    }

    private INDArray getLabels(Collection<Collection<Writable>> labels) {
        //Size of the record?
        int[] shape = new int[2];   //[timeSeriesLength,vectorSize]
        shape[0] = labels.size();   //time series/sequence length

        Iterator<Collection<Writable>> iter = labels.iterator();

        int i = 0;
        INDArray out = null;
        while (iter.hasNext()) {
            Collection<Writable> step = iter.next();
            if (i == 0) {
                if (regression) {
                    shape[1] = step.size();
                } else {
                    shape[1] = numPossibleLabels;
                }
                out = Nd4j.create(shape);
            }

            Iterator<Writable> timeStepIter = step.iterator();
            int f = 0;
            if (regression) {
                //Load all values
                while (timeStepIter.hasNext()) {
                    Writable current = timeStepIter.next();
                    out.put(i, f++, current.toDouble());
                }
            } else {
                //Expect a single value (index) -> convert to one-hot vector
                Writable value = timeStepIter.next();
                int idx = value.toInt();
                INDArray line = FeatureUtil.toOutcomeVector(idx, numPossibleLabels);
                out.getRow(i).assign(line);
            }

            i++;
        }
        return out;
    }

    private INDArray[] getFeaturesLabelsSingleReader(Collection<Collection<Writable>> input){
        Iterator<Collection<Writable>> iter = input.iterator();

        int i=0;
        INDArray features = null;
        INDArray labels = Nd4j.zeros(input.size(), regression ? 1 : numPossibleLabels);

        while(iter.hasNext()){
            Collection<Writable> step = iter.next();
            if (i == 0) {
                features = Nd4j.zeros( input.size(), step.size()-1);
            }

            Iterator<Writable> timeStepIter = step.iterator();
            int countIn = 0;
            int countFeatures = 0;
            while (timeStepIter.hasNext()) {
                Writable current = timeStepIter.next();
                if(countIn++ == labelIndex){
                    //label
                    if(regression){
                        labels.put(i,0,current.toDouble());
                    } else {
                        INDArray line = FeatureUtil.toOutcomeVector(current.toInt(), numPossibleLabels);
                        labels.putRow(i, line);
                    }
                } else {
                    //feature
                    features.put(i, countFeatures++, current.toDouble());
                }
            }
            i++;
        }

        return new INDArray[]{features,labels};
    }
}
