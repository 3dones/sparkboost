
/*
 *
 * ****************
 * This file is part of sparkboost software package (https://github.com/tizfa/sparkboost).
 *
 * Copyright 2016 Tiziano Fagni (tiziano.fagni@isti.cnr.it)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************
 */

package it.tizianofagni.sparkboost;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A Spark implementation of AdaBoost.MH learner.<br/><br/>
 * The original article describing the algorithm can be found at
 * <a href="http://link.springer.com/article/10.1023%2FA%3A1007649029923">http://link.springer.com/article/10.1023%2FA%3A1007649029923</a>.
 *
 * @author Tiziano Fagni (tiziano.fagni@isti.cnr.it)
 */
public class AdaBoostMHLearner {

    private final JavaSparkContext sc;
    /**
     * The number of iterations.
     */
    private int numIterations;

    /**
     * The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<MultilabelPoint>}.
     */
    private int numDocumentsPartitions;

    /**
     * The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.FeatureDocuments>}
     */
    private int numFeaturesPartitions;

    /**
     * The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.LabelDocuments>}
     */
    private int numLabelsPartitions;


    public AdaBoostMHLearner(JavaSparkContext sc) {
        if (sc == null)
            throw new NullPointerException("The SparkContext is 'null'");
        this.sc = sc;
        this.numIterations = 200;
        this.numDocumentsPartitions = -1;
        this.numFeaturesPartitions = -1;
        this.numLabelsPartitions = -1;
    }

    /**
     * Get the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<MultilabelPoint>}.
     *
     * @return The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<MultilabelPoint>}.
     */
    public int getNumDocumentsPartitions() {
        return numDocumentsPartitions;
    }

    /**
     * Set the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<MultilabelPoint>}.
     *
     * @param numDocumentsPartitions The number of partitions.
     */
    public void setNumDocumentsPartitions(int numDocumentsPartitions) {
        this.numDocumentsPartitions = numDocumentsPartitions;
    }


    /**
     * Get the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.FeatureDocuments>}.
     *
     * @return The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.FeatureDocuments>}
     */
    public int getNumFeaturesPartitions() {
        return numFeaturesPartitions;
    }

    /**
     * Set the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.FeatureDocuments>}.
     *
     * @param numFeaturesPartitions The number of partitions.
     */
    public void setNumFeaturesPartitions(int numFeaturesPartitions) {
        this.numFeaturesPartitions = numFeaturesPartitions;
    }


    /**
     * Get the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.LabelDocuments>}.
     *
     * @return The number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.LabelDocuments>}.
     */
    public int getNumLabelsPartitions() {
        return numLabelsPartitions;
    }

    /**
     * Set the number of partitions while analyzing
     * an RDD of type {@link JavaRDD<DataUtils.LabelDocuments>}.
     *
     * @param numLabelsPartitions The number of partitions.
     */
    public void setNumLabelsPartitions(int numLabelsPartitions) {
        this.numLabelsPartitions = numLabelsPartitions;
    }

    /**
     * Build a new classifier by analyzing the training data available in the
     * specified documents set.
     *
     * @param docs The set of documents used as training data.
     * @return A new AdaBoost.MH classifier.
     */
    public BoostClassifier buildModel(JavaRDD<MultilabelPoint> docs) {
        if (docs == null)
            throw new NullPointerException("The set of input documents is 'null'");


        // Repartition documents.
        Logging.l().info("Load initial data and generating internal data representations...");
        docs = docs.repartition(getNumDocumentsPartitions());
        docs = docs.persist(StorageLevel.MEMORY_AND_DISK_SER());
        Logging.l().info("Docs: num partitions " + docs.partitions().size());

        int numDocs = DataUtils.getNumDocuments(docs);
        int numLabels = DataUtils.getNumLabels(docs);
        JavaRDD<DataUtils.LabelDocuments> labelDocuments = DataUtils.getLabelDocuments(docs);

        // Repartition labels.
        labelDocuments = labelDocuments.repartition(getNumLabelsPartitions());
        labelDocuments = labelDocuments.persist(StorageLevel.MEMORY_AND_DISK_SER());
        Logging.l().info("Labels: num partitions " + labelDocuments.partitions().size());

        // Repartition features.
        JavaRDD<DataUtils.FeatureDocuments> featureDocuments = DataUtils.getFeatureDocuments(docs);
        featureDocuments = featureDocuments.repartition(getNumFeaturesPartitions());
        featureDocuments = featureDocuments.persist(StorageLevel.MEMORY_AND_DISK_SER());
        Logging.l().info("Features: num partitions " + featureDocuments.partitions().size());
        Logging.l().info("Ok, done!");

        WeakHypothesis[] computedWH = new WeakHypothesis[numIterations];
        double[][] localDM = initDistributionMatrix(numLabels, numDocs);
        for (int i = 0; i < numIterations; i++) {

            // Generate new weak hypothesis.
            WeakHypothesis localWH = learnWeakHypothesis(localDM, labelDocuments, featureDocuments);

            // Update distribution matrix with the new hypothesis.
            updateDistributionMatrix(sc, docs, localDM, localWH);

            // Save current generated weak hypothesis.
            computedWH[i] = localWH;

            Logging.l().info("Completed iteration " + (i + 1));
        }

        Logging.l().info("Model built!");

        return new BoostClassifier(computedWH);
    }


    /**
     * Build a new classifier by analyzing the training data available in the
     * specified input file. The file must be in LibSvm data format.
     *
     * @param libSvmFile    The input file containing the documents used as training data.
     * @param labels0Based  True if the label indexes specified in the input file are 0-based (i.e. the first label ID is 0), false if they
     *                      are 1-based (i.e. the first label ID is 1).
     * @param binaryProblem True if the input file contains data for a binary problem, false if the input file contains data for a multiclass multilabel
     *                      problem.
     * @return A new AdaBoost.MH classifier.
     */
    public BoostClassifier buildModel(String libSvmFile, boolean labels0Based, boolean binaryProblem) {
        if (libSvmFile == null || libSvmFile.isEmpty())
            throw new IllegalArgumentException("The input file is 'null' or empty");

        int minNumPartitions = 8;
        if (this.numDocumentsPartitions != -1)
            minNumPartitions = this.numDocumentsPartitions;
        JavaRDD<MultilabelPoint> docs = DataUtils.loadLibSvmFileFormatData(sc, libSvmFile, labels0Based, binaryProblem, minNumPartitions);
        if (this.numDocumentsPartitions == -1)
            this.numDocumentsPartitions = sc.defaultParallelism();
        if (this.numFeaturesPartitions == -1)
            this.numFeaturesPartitions = sc.defaultParallelism();
        if (this.numLabelsPartitions == -1)
            this.numLabelsPartitions = sc.defaultParallelism();
        Logging.l().info("Docs partitions = " + this.numDocumentsPartitions + ", feats partitions = " + this.numFeaturesPartitions + ", labels partitions = " + this.getNumLabelsPartitions());
        return buildModel(docs);
    }

    protected void updateDistributionMatrix(JavaSparkContext sc, JavaRDD<MultilabelPoint> docs, double[][] localDM, WeakHypothesis localWH) {
        Broadcast<WeakHypothesis> distWH = sc.broadcast(localWH);
        Broadcast<double[][]> distDM = sc.broadcast(localDM);
        JavaRDD<DMPartialResult> partialResults = docs.map(doc -> {
            int[] validFeatures = doc.getFeatures().indices();
            HashMap<Integer, Integer> dictFeatures = new HashMap<>();
            for (int featID : validFeatures)
                dictFeatures.put(featID, featID);
            HashMap<Integer, Integer> dictLabels = new HashMap<>();
            for (int idx = 0; idx < doc.getLabels().length; idx++)
                dictLabels.put(doc.getLabels()[idx], doc.getLabels()[idx]);

            double[][] dm = distDM.getValue();
            WeakHypothesis wh = distWH.getValue();
            double[] labelsRes = new double[dm.length];
            for (int labelID = 0; labelID < dm.length; labelID++) {
                float catValue = 1;
                if (dictLabels.containsKey(labelID)) {
                    catValue = -1;
                }

                // Compute the weak hypothesis value.
                double value = 0;
                WeakHypothesis.WeakHypothesisData v = wh.getLabelData(labelID);
                int pivot = v.getFeatureID();
                if (dictFeatures.containsKey(pivot))
                    value = v.getC1();
                else
                    value = v.getC0();


                double partialRes = dm[labelID][doc.getPointID()] * Math.exp(catValue * value);
                labelsRes[labelID] = partialRes;
            }

            return new DMPartialResult(doc.getPointID(), labelsRes);
        });

        Iterator<DMPartialResult> itResults = partialResults.toLocalIterator();
        // Update partial results.
        double normalization = 0;
        while (itResults.hasNext()) {
            DMPartialResult r = itResults.next();
            for (int labelID = 0; labelID < localDM.length; labelID++) {
                localDM[labelID][r.docID] = r.labelsRes[labelID];
                normalization += localDM[labelID][r.docID];
            }
        }

        // Normalize all values.
        for (int labelID = 0; labelID < localDM.length; labelID++) {
            for (int docID = 0; docID < localDM[0].length; docID++) {
                localDM[labelID][docID] = localDM[labelID][docID] / normalization;
            }
        }
    }

    protected double[][] initDistributionMatrix(int numLabels, int numDocs) {
        double[][] dist = new double[numLabels][numDocs];

        // Initialize matrix with uniform distribution.
        float uniformValue = 1 / ((float) numDocs * numLabels);
        for (int label = 0; label < dist.length; label++) {
            for (int doc = 0; doc < dist[0].length; doc++) {
                dist[label][doc] = uniformValue;
            }
        }
        return dist;
    }

    protected WeakHypothesis learnWeakHypothesis(double[][] localDM, JavaRDD<DataUtils.LabelDocuments> labelDocuments, JavaRDD<DataUtils.FeatureDocuments> featureDocuments) {
        int labelsSize = localDM.length;
        int docsSize = localDM[0].length;

        // Examples positive for a given label.
        double[] local_weight_b1 = new double[labelsSize];

        // Examples negative for a given label
        double[] local_weight_bminus_1 = new double[labelsSize];


        // Initialize structures.
        for (int pos = 0; pos < labelsSize; pos++) {
            local_weight_b1[pos] = 0;
            local_weight_bminus_1[pos] = 0;
        }

        Iterator<DataUtils.LabelDocuments> itlabels = labelDocuments.toLocalIterator();
        while (itlabels.hasNext()) {
            DataUtils.LabelDocuments la = itlabels.next();
            int labelID = la.getLabelID();
            assert (labelID != -1);
            for (int idx = 0; idx < la.getDocuments().length; idx++) {
                int docID = la.getDocuments()[idx];
                assert (docID != -1);
                double distValue = localDM[labelID][docID];
                local_weight_b1[labelID] += distValue;
            }
        }


        // Compute global weight for categories.
        for (int labelID = 0; labelID < labelsSize; labelID++) {
            double global = 0;

            // Iterate over all distribution matrix.
            for (int docID = 0; docID < docsSize; docID++) {
                double distValue = localDM[labelID][docID];
                global += distValue;
            }

            local_weight_bminus_1[labelID] = global - local_weight_b1[labelID];
        }


        Broadcast<double[][]> distDM = sc.broadcast(localDM);
        Broadcast<double[]> weight_b1 = sc.broadcast(local_weight_b1);
        Broadcast<double[]> weight_bminus_1 = sc.broadcast(local_weight_bminus_1);

        // Process all features.
        WeakHypothesisResults res = featureDocuments.map(feat -> {
            double[][] dm = distDM.getValue();
            double epsilon = 1.0 / (double) (dm.length * dm[0].length);
            int numLabels = dm.length;
            double[] weight_b1_x0 = new double[numLabels];
            double[] weight_b1_x1 = new double[numLabels];
            double[] weight_bminus_1_x0 = new double[numLabels];
            double[] weight_bminus_1_x1 = new double[numLabels];
            double[] computedC0 = new double[numLabels];
            double[] computedC1 = new double[numLabels];
            double[] computedZs = new double[numLabels];
            int realFeatID = feat.getFeatureID();
            int pivot = realFeatID;
            // Initialize structures.
            for (int pos = 0; pos < numLabels; pos++) {
                weight_b1_x0[pos] = 0;
                weight_b1_x1[pos] = 0;
                weight_bminus_1_x0[pos] = 0;
                weight_bminus_1_x1[pos] = 0;
            }


            for (int docIdx = 0; docIdx < feat.getDocuments().length; docIdx++) {
                int docID = feat.getDocuments()[docIdx];
                int[] labels = feat.getLabels()[docIdx];
                HashMap<Integer, Integer> catDict = new HashMap<Integer, Integer>();
                for (int labelIdx = 0; labelIdx < labels.length; labelIdx++) {
                    int currentCatID = labels[labelIdx];
                    double distValue = dm[currentCatID][docID];
                    // Feature and category compare together.
                    weight_b1_x1[currentCatID] += distValue;
                    catDict.put(currentCatID, currentCatID);
                }
                for (int currentCatID = 0; currentCatID < numLabels; currentCatID++) {
                    if (catDict.containsKey(currentCatID))
                        continue;
                    double distValue = dm[currentCatID][docID];
                    // Feature compare on document and category not.
                    weight_bminus_1_x1[currentCatID] += distValue;
                }
            }

            // Compute the remaining values.
            for (int catID = 0; catID < numLabels; catID++) {
                double v = weight_b1.getValue()[catID] - weight_b1_x1[catID];
                if (v < 0)
                    v = 0;

                weight_b1_x0[catID] = v;

                v = weight_bminus_1.getValue()[catID] - weight_bminus_1_x1[catID];
                // Adjust round errors.
                if (v < 0)
                    v = 0;
                weight_bminus_1_x0[catID] = v;
            }

            // Compute current Z_s.
            double Z_s = 0;
            for (int catID = 0; catID < numLabels; catID++) {
                assert (weight_b1_x0[catID] >= 0);
                assert (weight_bminus_1_x0[catID] >= 0);
                assert (weight_b1_x1[catID] >= 0);
                assert (weight_bminus_1_x1[catID] >= 0);

                double first = Math.sqrt(weight_b1_x0[catID]
                        * weight_bminus_1_x0[catID]);
                double second = Math.sqrt(weight_b1_x1[catID]
                        * weight_bminus_1_x1[catID]);
                Z_s += (first + second);
                double c0 = Math.log((weight_b1_x0[catID] + epsilon)
                        / (weight_bminus_1_x0[catID] + epsilon)) / 2.0;
                double c1 = Math.log((weight_b1_x1[catID] + epsilon)
                        / (weight_bminus_1_x1[catID] + epsilon)) / 2.0;
                computedC0[catID] = c0;
                computedC1[catID] = c1;
            }
            Z_s = 2 * Z_s;

            return new WeakHypothesisResults(pivot, computedC0, computedC1, Z_s);
        }).reduce((ph1, ph2) -> {
            int pivot = -1;
            double[] c0 = new double[ph1.getC0().length];
            double[] c1 = new double[ph1.getC0().length];
            double z_s = 0;
            if (ph1.getZ_s() < ph2.getZ_s()) {
                pivot = ph1.pivot;
                z_s = ph1.getZ_s();
                for (int i = 0; i < ph1.getC0().length; i++) {
                    c0[i] = ph1.getC0()[i];
                    c1[i] = ph1.getC1()[i];
                }
            } else {
                pivot = ph2.pivot;
                z_s = ph2.getZ_s();
                for (int i = 0; i < ph2.getC0().length; i++) {
                    c0[i] = ph2.getC0()[i];
                    c1[i] = ph2.getC1()[i];
                }
            }

            return new WeakHypothesisResults(pivot, c0, c1, z_s);
        });

        WeakHypothesis wh = new WeakHypothesis(labelsSize);
        for (int i = 0; i < labelsSize; i++) {
            wh.setLabelData(i, new WeakHypothesis.WeakHypothesisData(i, res.getPivot(), res.getC0()[i], res.getC1()[i]));
        }
        return wh;
    }

    /**
     * Get the number of iterations used while building classifier.
     *
     * @return The number of iterations used while building classifier.
     */
    public int getNumIterations() {
        return numIterations;
    }

    /**
     * Set the number of iterations to use while building a new classifier.
     *
     * @param numIterations The number of iterations to use.
     */
    public void setNumIterations(int numIterations) {
        this.numIterations = numIterations;
    }


    private static class WeakHypothesisResults implements Serializable {
        private final int pivot;
        private final double[] c0;
        private final double[] c1;
        private final double z_s;

        public WeakHypothesisResults(int pivot, double[] c0, double[] c1, double z_s) {
            this.pivot = pivot;
            this.c0 = c0;
            this.c1 = c1;
            this.z_s = z_s;
        }

        public int getPivot() {
            return pivot;
        }

        public double[] getC0() {
            return c0;
        }

        public double[] getC1() {
            return c1;
        }

        public double getZ_s() {
            return z_s;
        }
    }
}
