package be.bagofwords.brown;

import be.bagofwords.application.status.perf.ThreadSampleMonitor;
import be.bagofwords.ui.UI;
import be.bagofwords.util.NumUtils;
import be.bagofwords.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableInt;

import java.io.*;
import java.util.*;

/**
 * Created by Koen Deschacht (koendeschacht@gmail.com) on 02/12/14.
 * <p>
 * Clustering algorithm of words and phrases described in:
 * Class Based n-gram Models of Natural Language, P.F. Brown, P.V. deSouza, R.L. Mercer, V.J.D. Pietra, J.C. Lai
 * http://people.csail.mit.edu/imcgraw/links/research/pubs/ClassBasedNGrams.pdf
 */

public class BrownCluster {

    public static void main(String[] args) throws IOException {
        String textInputFile = "/home/koen/input_news_small.txt";
        String wordAssignmentsFile = "/home/koen/brown_output4.txt";
        int minFrequencyOfPhrase = 10;
        int maxNumberOfClusters = 500;
        long start = System.currentTimeMillis();
        new BrownCluster(textInputFile, wordAssignmentsFile, minFrequencyOfPhrase, maxNumberOfClusters).run();
        long end = System.currentTimeMillis();
        UI.write("Took " + (end - start) + " ms.");
    }

    private static final String UNKNOWN_PHRASE = "_UNKNOWN_";
    public static final boolean DO_TESTS = false; //you probably want to enable this during development

    private final String textInputFile;
    private final String wordAssignmentsFile;
    private final int minFrequencyOfPhrase;
    private final int maxNumberOfClusters;

    public BrownCluster(String textInputFile, String wordAssignmentsFile, int minFrequencyOfPhrase, int maxNumberOfClusters) {
        this.textInputFile = textInputFile;
        this.wordAssignmentsFile = wordAssignmentsFile;
        this.minFrequencyOfPhrase = minFrequencyOfPhrase;
        this.maxNumberOfClusters = maxNumberOfClusters;
    }

    private void run() throws IOException {
        ThreadSampleMonitor threadSampleMonitor = new ThreadSampleMonitor(true, "threadSamples.txt", "brown-cluster");
        Map<Integer, String> phraseMap = readAllPhrases(textInputFile);
        UI.write("Read " + phraseMap.size() + " phrases.");
        ContextCountsImpl contextCounts = extractContextCounts(phraseMap, textInputFile);
        doClustering(phraseMap, contextCounts);
        threadSampleMonitor.terminate();
    }

    private void doClustering(Map<Integer, String> phraseMap, ContextCountsImpl phraseContextCounts) throws IOException {
        Int2IntOpenHashMap phraseToClusterMap = initializeClusters(phraseMap.size());
        ContextCountsImpl clusterContextCounts = phraseContextCounts.clone(); //initially these counts are identical
        Map<Integer, String> clusterNames = initializeClusterNames(phraseMap, phraseToClusterMap);
        if (DO_TESTS) {
            TestUtils.checkCounts(clusterContextCounts, phraseToClusterMap, phraseContextCounts);
        }
        mergeInfrequentPhrasesWithFrequentPhraseClusters(clusterNames, phraseToClusterMap, clusterContextCounts);
        swapPhrases(phraseToClusterMap, clusterContextCounts, phraseContextCounts);
        Map<Integer, ClusterHistoryNode> leafHistoryNodes = initializeHistoryNodes(phraseToClusterMap);
        Map<Integer, ClusterHistoryNode> historyNodesInProgress = new HashMap<>(leafHistoryNodes);
        UI.write("Starting merge phase 1");
        //iterativelyMergeClusters(maxNumberOfClusters, Integer.MAX_VALUE, 0, maxNumberOfClusters, clusterNames, historyNodesInProgress, clusterContextCounts);
        UI.write("Starting merge phase 2");
        iterativelyMergeClusters(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, clusterNames, historyNodesInProgress, clusterContextCounts);
        writeOutput(phraseMap, phraseToClusterMap, leafHistoryNodes);
    }


    private Map<Integer, String> initializeClusterNames(Map<Integer, String> phraseMap, Int2IntOpenHashMap phraseToClusterMap) {
        Map<Integer, String> clusterNames = new HashMap<>();
        for (Map.Entry<Integer, String> entry : phraseMap.entrySet()) {
            Integer cluster = phraseToClusterMap.get(entry.getKey());
            String clusterName = clusterNames.get(cluster);
            if (clusterName == null) {
                clusterNames.put(cluster, entry.getValue());
            } else if (clusterName.length() < 30) {
                clusterNames.put(cluster, clusterName + " " + entry.getValue());
            }
        }
        return clusterNames;
    }

    private void writeOutput(Map<Integer, String> phraseMap, Int2IntOpenHashMap phraseToClusterMap, Map<Integer, ClusterHistoryNode> nodes) throws IOException {
        List<String> outputLines = new ArrayList<>();
        for (Integer phraseInd : phraseToClusterMap.keySet()) {
            String phrase = phraseMap.get(phraseInd);
            String output = "";
            ClusterHistoryNode node = nodes.get(phraseToClusterMap.get(phraseInd));
            while (node != null) {
                ClusterHistoryNode parent = node.getParent();
                if (parent != null) {
                    if (parent.getLeftChild() == node) {
                        output = '0' + output;
                    } else {
                        output = '1' + output;
                    }
                }
                node = parent;
            }
            outputLines.add(output + '\t' + phrase + " " + phraseInd);
        }
        Collections.sort(outputLines);
        BufferedWriter writer = new BufferedWriter(new FileWriter(wordAssignmentsFile));
        for (String line : outputLines) {
            writer.write(line);
            writer.write('\n');
        }
        writer.close();
    }

    private void swapPhrases(Int2IntOpenHashMap phraseToClusterMap, ContextCountsImpl clusterContextCounts, ContextCountsImpl phraseContextCounts) {
        boolean finished = false;
        while (!finished) {
            finished = true;
            for (int phrase = 0; phrase < phraseContextCounts.getNumberOfPhrases(); phrase++) {
                int currCluster = phraseToClusterMap.get(phrase);
                ContextCountsImpl contextCountsForPhrase = mapPhraseCountsToClusterCounts(phrase, phraseToClusterMap, phraseContextCounts, SwapWordContextCounts.DUMMY_CLUSTER);
                SwapWordContextCounts swapWordContextCounts = new SwapWordContextCounts(clusterContextCounts, contextCountsForPhrase, currCluster);
                Pair<Integer, Double> bestClusterScore = findBestClusterToMerge(SwapWordContextCounts.DUMMY_CLUSTER, 0, Integer.MAX_VALUE, swapWordContextCounts);
                double oldScore = computeMergeScore(SwapWordContextCounts.DUMMY_CLUSTER, 0.0, currCluster, swapWordContextCounts);
                if (bestClusterScore.getFirst() != currCluster && bestClusterScore.getSecond() > oldScore + 1e-10) {
                    //if the best cluster is not the current one, we merge our counts
                    int newCluster = bestClusterScore.getFirst();
                    UI.write("Assigning phrase " + phrase + " to cluster " + newCluster + " (was cluster " + currCluster + ")");
                    phraseToClusterMap.put(phrase, newCluster);
                    clusterContextCounts.removeCounts(contextCountsForPhrase.mapCluster(SwapWordContextCounts.DUMMY_CLUSTER, currCluster));
                    clusterContextCounts.addCounts(contextCountsForPhrase.mapCluster(SwapWordContextCounts.DUMMY_CLUSTER, newCluster));
                    if (DO_TESTS) {
                        TestUtils.checkCounts(clusterContextCounts, phraseToClusterMap, phraseContextCounts);
                        checkSwapScores(phraseToClusterMap, clusterContextCounts, phraseContextCounts, phrase, currCluster, bestClusterScore, oldScore, newCluster);
                    }
                    finished = false;

                }
            }
        }
    }

    private void checkSwapScores(Int2IntOpenHashMap phraseToClusterMap, ContextCountsImpl clusterContextCounts, ContextCountsImpl phraseContextCounts, int phrase, int currCluster, Pair<Integer, Double> bestClusterScore, double oldScore, int newCluster) {
        ContextCountsImpl debugContextCountsForPhrase = mapPhraseCountsToClusterCounts(phrase, phraseToClusterMap, phraseContextCounts, SwapWordContextCounts.DUMMY_CLUSTER);
        SwapWordContextCounts debugSwapWordContextCounts = new SwapWordContextCounts(clusterContextCounts, debugContextCountsForPhrase, newCluster);
        double debugOldScore = computeMergeScore(SwapWordContextCounts.DUMMY_CLUSTER, 0.0, currCluster, debugSwapWordContextCounts);
        double debugNewScore = computeMergeScore(SwapWordContextCounts.DUMMY_CLUSTER, 0.0, newCluster, debugSwapWordContextCounts);
        if (!NumUtils.equal(debugOldScore, oldScore)) {
            throw new RuntimeException("Inconsistent score! " + oldScore + " " + debugOldScore);
        }
        if (!NumUtils.equal(debugNewScore, bestClusterScore.getSecond())) {
            throw new RuntimeException("Inconsistent score! " + bestClusterScore.getSecond() + " " + debugNewScore);
        }
    }

    private Map<Integer, ClusterHistoryNode> initializeHistoryNodes(Int2IntOpenHashMap phraseToClusterMap) {
        Map<Integer, ClusterHistoryNode> result = new HashMap<>();
        for (Integer cluster : phraseToClusterMap.values()) {
            result.put(cluster, new ClusterHistoryNode());
        }
        return result;
    }

    private ContextCountsImpl mapPhraseCountsToClusterCounts(int phrase, Int2IntOpenHashMap phraseToClusterMap, ContextCounts phraseContextCounts, int newCluster) {
        Map<Integer, Int2IntOpenHashMap> prevClusterCounts = new HashMap<>();
        Map<Integer, Int2IntOpenHashMap> nextClusterCounts = new HashMap<>();
        addCounts(phraseToClusterMap, phraseContextCounts.getPrevCounts(phrase), prevClusterCounts, nextClusterCounts, phrase, true, newCluster);
        addCounts(phraseToClusterMap, phraseContextCounts.getNextCounts(phrase), nextClusterCounts, prevClusterCounts, phrase, false, newCluster);
        return new ContextCountsImpl(prevClusterCounts, nextClusterCounts);
    }

    private void addCounts(Int2IntOpenHashMap phraseToClusterMap, Int2IntOpenHashMap phraseContextCounts, Map<Integer, Int2IntOpenHashMap> prevClusterCounts, Map<Integer, Int2IntOpenHashMap> nextClusterCounts, int phrase, boolean includeIdentityCounts, int newCluster) {
        Int2IntOpenHashMap phrasePrevClusterCounts = prevClusterCounts.get(newCluster);
        if (phrasePrevClusterCounts == null) {
            phrasePrevClusterCounts = MapUtils.createNewInt2IntMap();
            prevClusterCounts.put(newCluster, phrasePrevClusterCounts);
        }
        for (Int2IntOpenHashMap.Entry otherPhraseEntry : phraseContextCounts.int2IntEntrySet()) {
            int otherPhrase = otherPhraseEntry.getIntKey();
            if (phrase != otherPhrase || includeIdentityCounts) {
                int clusterOtherPhrase = otherPhrase == phrase ? newCluster : phraseToClusterMap.get(otherPhrase);
                phrasePrevClusterCounts.addTo(clusterOtherPhrase, otherPhraseEntry.getIntValue());
                Int2IntOpenHashMap otherPhraseNextCounts = nextClusterCounts.get(clusterOtherPhrase);
                if (otherPhraseNextCounts == null) {
                    otherPhraseNextCounts = MapUtils.createNewInt2IntMap();
                    nextClusterCounts.put(clusterOtherPhrase, otherPhraseNextCounts);
                }
                otherPhraseNextCounts.addTo(newCluster, otherPhraseEntry.getValue());
            }
        }
    }


    private void iterativelyMergeClusters(int mergeCandidateStart, int mergeCandidateEnd, int mergeDestinationStart, int mergeDestinationEnd, Map<Integer, String> clusterNames, Map<Integer, ClusterHistoryNode> nodes, ContextCountsImpl contextCounts) {
        List<MergeCandidate> mergeCandidates = computeAllScores(mergeCandidateStart, mergeCandidateEnd, mergeDestinationStart, mergeDestinationEnd, contextCounts);
        while (!mergeCandidates.isEmpty()) {
            MergeCandidate next = mergeCandidates.remove(mergeCandidates.size() - 1);
            int cluster1 = next.getCluster1();
            int cluster2 = next.getCluster2();
            UI.write("Will merge " + cluster1 + " (" + clusterNames.get(cluster1) + ") with " + cluster2 + " (" + clusterNames.get(cluster2) + ")");
            mergeClusters(contextCounts, clusterNames, cluster1, cluster2);
            updateClusterNodes(nodes, cluster1, cluster2);
            removeMergeCandidates(mergeCandidates, cluster1);
            updateMergeCandidateScores(cluster2, mergeCandidates, contextCounts);
        }
    }

    private void removeMergeCandidates(List<MergeCandidate> mergeCandidates, int smallCluster) {
        mergeCandidates.removeIf(next -> next.getCluster1() == smallCluster || next.getCluster2() == smallCluster);
    }

    private void updateClusterNodes(Map<Integer, ClusterHistoryNode> nodes, int smallCluster, int largeCluster) {
        ClusterHistoryNode parent = new ClusterHistoryNode();
        parent.setChildren(nodes.remove(smallCluster), nodes.get(largeCluster));
        nodes.put(largeCluster, parent);
    }

    private List<MergeCandidate> computeAllScores(int mergeCandidateStart, int mergeCandidateEnd, int mergeDestinationStart, int mergeDestinationEnd, ContextCounts contextCounts) {
        List<MergeCandidate> mergeCandidates = Collections.synchronizedList(new ArrayList<>());
        Set<Integer> allClusters = contextCounts.getAllClusters();
        allClusters.parallelStream().forEach(cluster1 -> {
            if (cluster1 >= mergeCandidateStart && cluster1 < mergeCandidateEnd) {
                double ski = computeSK(cluster1, contextCounts);
                for (Integer cluster2 : allClusters) {
                    if (cluster2 >= mergeDestinationStart && cluster2 < mergeDestinationEnd) {
                        if (cluster2 < cluster1) {
                            double score = computeMergeScore(cluster1, ski, cluster2, contextCounts);
                            mergeCandidates.add(new MergeCandidate(cluster1, cluster2, score));
                        }
                    }
                }
            }
        });
        Collections.sort(mergeCandidates);
        return mergeCandidates;
    }

    private void updateMergeCandidateScores(int cluster2, List<MergeCandidate> mergeCandidates, ContextCounts contextCounts) {
        double skj = computeSK(cluster2, contextCounts);
        mergeCandidates.parallelStream().forEach(mergeCandidate -> {
                    if (mergeCandidate.getCluster2() == cluster2) {
                        double ski = computeSK(mergeCandidate.getCluster1(), contextCounts);
                        mergeCandidate.setScore(computeMergeScore(mergeCandidate.getCluster1(), ski, mergeCandidate.getCluster2(), skj, contextCounts));
                    }
                }
        );
        Collections.sort(mergeCandidates);
    }

    private void mergeInfrequentPhrasesWithFrequentPhraseClusters(Map<Integer, String> clusterNames, Int2IntOpenHashMap phraseToClusterMap, ContextCountsImpl clusterContextCounts) {
        for (int phrase = maxNumberOfClusters; phrase < phraseToClusterMap.size(); phrase++) {
            int newCluster = findBestClusterToMerge(phrase, 0, maxNumberOfClusters, clusterContextCounts).getFirst();
            UI.write("Will merge cluster " + phrase + " (" + clusterNames.get(phrase) + ") with " + newCluster + " (" + clusterNames.get(newCluster) + ")");
            mergeClusters(clusterContextCounts, clusterNames, phrase, newCluster);
            phraseToClusterMap.put(phrase, newCluster);
        }
    }

    private void mergeClusters(ContextCountsImpl clusterContextCounts, Map<Integer, String> clusterNames, int smallCluster, int largeCluster) {
        String currName = clusterNames.get(largeCluster);
        if (currName.length() < 30) {
            clusterNames.put(largeCluster, currName + " " + clusterNames.get(smallCluster));
        }
        clusterContextCounts.mergeClusters(smallCluster, largeCluster);
    }

    private Pair<Integer, Double> findBestClusterToMerge(int origCluster, int minCluster, int maxCluster, ContextCounts clusterContextCounts) {
        Object syncLock = new Object();
        MutableDouble bestScore = new MutableDouble(-Double.MAX_VALUE);
        MutableInt bestCluster = new MutableInt(-1);
        clusterContextCounts.getAllClusters().parallelStream().forEach(cluster -> {
            if (cluster >= minCluster && cluster < maxCluster && cluster != origCluster) {
                double score = computeMergeScore(origCluster, 0.0, cluster, clusterContextCounts);
                if (score > bestScore.doubleValue()) {
                    synchronized (syncLock) {
                        if (score > bestScore.doubleValue()) { //bestScore might have changed while we acquiring the lock
                            bestScore.setValue(score);
                            bestCluster.setValue(cluster);
                        }
                    }
                }

            }
        });
        return new Pair<>(bestCluster.intValue(), bestScore.doubleValue());
    }

    /**
     * see top of page 7 of [Brown et al.].
     */

    private double computeMergeScore(int cki, double ski, int ckj, ContextCounts contextCounts) {
        return computeMergeScore(cki, ski, ckj, computeSK(ckj, contextCounts), contextCounts);
    }

    private double computeMergeScore(int cki, double ski, int ckj, double skj, ContextCounts originalCounts) {
        MergedContextCounts mergedCounts = new MergedContextCounts(cki, ckj, originalCounts);
        double result = -ski - skj;
        result += computeSK(ckj, mergedCounts);
        return result;
    }

    private double computeSK(int cluster, ContextCounts contextCounts) {
        double sk = 0;
        double grandTotal = contextCounts.getGrandTotal();
        int prevTotal = contextCounts.getPrevTotal(cluster);
        for (Int2IntOpenHashMap.Entry entry : contextCounts.getPrevCounts(cluster).int2IntEntrySet()) {
            sk += computeQK(entry.getIntValue(), contextCounts.getNextTotal(entry.getIntKey()), prevTotal, grandTotal);
        }
        Int2IntOpenHashMap nextCounts = contextCounts.getNextCounts(cluster);
        int nextTotal = contextCounts.getNextTotal(cluster) - nextCounts.get(cluster);
        for (Int2IntOpenHashMap.Entry entry : nextCounts.int2IntEntrySet()) {
            if (entry.getIntKey() != cluster) {
                sk += computeQK(entry.getIntValue(), nextTotal, contextCounts.getPrevTotal(entry.getIntKey()), grandTotal);
            }
        }
        return sk;
    }

    private double computeQK(int jointCounts, int totalCki, int totalCkj, double grandTotal) {
        if (jointCounts > 0) {
            double pklm = jointCounts / grandTotal;
            double plkl = totalCki / grandTotal;
            double prkm = totalCkj / grandTotal;
            checkProbability(pklm);
            checkProbability(plkl);
            checkProbability(prkm);
            if (plkl == 0 || prkm == 0) {
                throw new RuntimeException("Illegal probabilities!");
            }
            return pklm * Math.log(pklm / (plkl * prkm));
        } else {
            return 0.0;
        }
    }


    private void checkProbability(double probability) {
        if (probability < 0 || probability > 1 || Double.isNaN(probability)) {
            throw new RuntimeException("Illegal probability " + probability);
        }
    }

    private Int2IntOpenHashMap initializeClusters(int numberOfPhrases) {
        Int2IntOpenHashMap phraseToCluster = MapUtils.createNewInt2IntMap(numberOfPhrases);
        for (int i = 0; i < numberOfPhrases; i++) {
            phraseToCluster.put(i, i); //assign every word to its own cluster
        }
        return phraseToCluster;
    }

    private Map<String, Integer> invert(Map<Integer, String> map) {
        Map<String, Integer> invertedMap = new HashMap<>(map.size());
        for (Map.Entry<Integer, String> entry : map.entrySet()) {
            invertedMap.put(entry.getValue(), entry.getKey());
        }
        return invertedMap;
    }

    private ContextCountsImpl extractContextCounts(Map<Integer, String> phraseMap, String textInputFile) throws IOException {
        Map<String, Integer> invertedPhraseMap = invert(phraseMap); //mapping of words to their index
        Map<Integer, Int2IntOpenHashMap> prevContextCounts = createEmptyCounts(phraseMap.size());
        Map<Integer, Int2IntOpenHashMap> nextContextCounts = createEmptyCounts(phraseMap.size());
        BufferedReader rdr = new BufferedReader(new FileReader(textInputFile));
        while (rdr.ready()) {
            String line = rdr.readLine();
            List<String> phrases = splitLineInPhrases(line);
            Integer prevPhrase = null;
            for (String phrase : phrases) {
                Integer currPhrase = invertedPhraseMap.get(phrase);
                if (currPhrase == null) {
                    //infrequent phrase
                    currPhrase = invertedPhraseMap.get(UNKNOWN_PHRASE);
                }
                if (prevPhrase != null) {
                    nextContextCounts.get(prevPhrase).addTo(currPhrase, 1);
                    prevContextCounts.get(currPhrase).addTo(prevPhrase, 1);
                }
                prevPhrase = currPhrase;
            }
        }
        rdr.close();
        trimCounts(prevContextCounts);
        trimCounts(nextContextCounts);
        return new ContextCountsImpl(prevContextCounts, nextContextCounts);
    }

    private void trimCounts(Map<Integer, Int2IntOpenHashMap> wordCounts) {
        wordCounts.values().stream().forEach(Int2IntOpenHashMap::trim);
    }

    private Map<Integer, Int2IntOpenHashMap> createEmptyCounts(int size) {
        Map<Integer, Int2IntOpenHashMap> result = new HashMap<>();
        for (int i = 0; i < size; i++) {
            result.put(i, MapUtils.createNewInt2IntMap());
        }
        return result;
    }


    private Map<Integer, String> readAllPhrases(String textInputFile) throws IOException {
        //Count how often every phrase occurs in the input
        Map<String, Integer> phraseCounts = countPhrases(textInputFile);
        //Select phrases that occur >= minFrequencyOfPhrase
        List<String> allPhrases = new ArrayList<>();
        int totalDroppedCounts = 0;
        for (Map.Entry<String, Integer> entry : phraseCounts.entrySet()) {
            if (entry.getValue() >= minFrequencyOfPhrase) {
                allPhrases.add(entry.getKey());
            } else {
                totalDroppedCounts += entry.getValue();
            }
        }
        if (totalDroppedCounts > 0) {
            allPhrases.add(UNKNOWN_PHRASE);
            phraseCounts.put(UNKNOWN_PHRASE, totalDroppedCounts);
        }
        //Return a map of every word to their (unique) index
        return assignWordsToIndexBasedOnFrequency(allPhrases, phraseCounts);
    }

    private Map<Integer, String> assignWordsToIndexBasedOnFrequency(List<String> allWords, Map<String, Integer> phraseCounts) {
        Collections.sort(allWords, (word1, word2) -> -Integer.compare(phraseCounts.get(word1), phraseCounts.get(word2)));
        Map<Integer, String> wordMapping = new HashMap<>();
        int ind = 0;
        for (String word : allWords) {
            wordMapping.put(ind++, word);
        }
        return wordMapping;
    }

    private Map<String, Integer> countPhrases(String textInputFile) throws IOException {
        Object2IntOpenHashMap<String> phraseCounts = new Object2IntOpenHashMap<>();
        BufferedReader rdr = new BufferedReader(new FileReader(textInputFile));
        while (rdr.ready()) {
            String line = rdr.readLine();
            List<String> phrases = splitLineInPhrases(line);
            for (String phrase : phrases) {
                phraseCounts.addTo(phrase, 1);
            }
        }
        rdr.close();
        return phraseCounts;
    }

    /**
     * Could be adapted to have phrases of more than 1 word (e.g. map collocations such as 'fast food' or 'prime minister' to a single phrase)
     */

    private List<String> splitLineInPhrases(String line) {
        String[] words = line.split("\\s");
        List<String> result = new ArrayList<>();
        for (String word : words) {
            if (!word.isEmpty()) {
                result.add(word);
            }
        }
        return result;
    }

}

