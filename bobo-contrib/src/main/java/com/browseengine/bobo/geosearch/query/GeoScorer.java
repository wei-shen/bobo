/**
 * This software is licensed to you under the Apache License, Version 2.0 (the
 * "Apache License").
 *
 * LinkedIn's contributions are made under the Apache License. If you contribute
 * to the Software, the contributions will be deemed to have been made under the
 * Apache License, unless you expressly indicate otherwise. Please do not make any
 * contributions that would be inconsistent with the Apache License.
 *
 * You may obtain a copy of the Apache License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, this software
 * distributed under the Apache License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Apache
 * License for the specific language governing permissions and limitations for the
 * software governed under the Apache License.
 *
 * © 2012 LinkedIn Corp. All Rights Reserved.  
 */

package com.browseengine.bobo.geosearch.query;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import com.browseengine.bobo.geosearch.CartesianCoordinateDocId;
import com.browseengine.bobo.geosearch.IDeletedDocs;
import com.browseengine.bobo.geosearch.IGeoBlockOfHitsProvider;
import com.browseengine.bobo.geosearch.IGeoConverter;
import com.browseengine.bobo.geosearch.bo.CartesianGeoRecord;
import com.browseengine.bobo.geosearch.bo.DocsSortedByDocId;
import com.browseengine.bobo.geosearch.bo.GeRecordAndCartesianDocId;
import com.browseengine.bobo.geosearch.impl.DeletedDocs;
import com.browseengine.bobo.geosearch.impl.GeoBlockOfHitsProvider;
import com.browseengine.bobo.geosearch.impl.GeoConverter;
import com.browseengine.bobo.geosearch.index.impl.GeoSegmentReader;
import com.browseengine.bobo.geosearch.score.impl.CartesianComputeDistance;
import com.browseengine.bobo.geosearch.score.impl.Conversions;

/**
 * @author Ken McCracken
 * @author shandets
 *
 */
public class GeoScorer extends Scorer {
    /**
     * Number of Documents we look at at a time.
     */
    private static final int BLOCK_SIZE = 16384;
    
    private static final int DOCID_CURSOR_NONE_YET = -1;

    private final IGeoConverter geoConverter;
    private final IGeoBlockOfHitsProvider geoBlockOfHitsProvider;
    private final List<GeoSegmentReader<CartesianGeoRecord>> segmentsInOrder;
    private final int centroidX;
    private final int centroidY;
    private final int centroidZ;
    private final int[] cartesianBoundingBox;
    
    // current pointers
    private int docid = DOCID_CURSOR_NONE_YET; 
    private int indexOfCurrentPartition = DOCID_CURSOR_NONE_YET;
    private int startDocidOfCurrentPartition;
    private GeoSegmentReader<CartesianGeoRecord> currentSegment = null;
    private DocsSortedByDocId currentBlockScoredDocs;
    private Entry<Integer, Collection<GeRecordAndCartesianDocId>> currentDoc;
    
    private IDeletedDocs wholeIndexDeletedDocs;
    
    
    public static void main(String args[]) {
        System.out.println("NO_MORE_DOCS equals to = " + NO_MORE_DOCS);
    }
    
    public GeoScorer(Weight weight,
                     List<GeoSegmentReader<CartesianGeoRecord>> segmentsInOrder, 
                     IDeletedDocs wholeIndexDeletedDocs, 
                     double centroidLatitude, 
                     double centroidLongitude,
                     float rangeInKm) {
        
        super(weight);
        double centroidLatitudeRadians = Conversions.d2r(centroidLatitude);
        double centroidLongitudeRadians = Conversions.d2r(centroidLongitude);
        this.geoConverter = new GeoConverter();
        this.geoBlockOfHitsProvider = new GeoBlockOfHitsProvider(geoConverter);
        
        this.segmentsInOrder = segmentsInOrder;
        
        this.centroidX = geoConverter.getXFromRadians(centroidLatitudeRadians, centroidLongitudeRadians);
        this.centroidY = geoConverter.getYFromRadians(centroidLatitudeRadians, centroidLongitudeRadians);
        this.centroidZ = geoConverter.getZFromRadians(centroidLatitudeRadians);
        
        startDocidOfCurrentPartition = -1;
        
        CartesianCoordinateDocId minccd = buildMinCoordinate(rangeInKm, centroidX, centroidY, centroidZ, 0);
        CartesianCoordinateDocId maxccd = buildMaxCoordinate(rangeInKm, centroidX, centroidY, centroidZ, 0);
        this.cartesianBoundingBox = new int [] {minccd.x, maxccd.x, minccd.y, maxccd.y, minccd.z, maxccd.z};
        
    }
    
    public static CartesianCoordinateDocId buildMinCoordinate(float rangeInKm, int x, int y, int z, int docid) {
        int rangeInUnits = Conversions.radiusMetersToIntegerUnits(rangeInKm * 1000.0);
        int minX = Conversions.calculateMinimumCoordinate(x, rangeInUnits);
        int minY = Conversions.calculateMinimumCoordinate(y, rangeInUnits);
        int minZ = Conversions.calculateMinimumCoordinate(z, rangeInUnits);
        return new CartesianCoordinateDocId(minX, minY, minZ, docid);
    }
    public static CartesianCoordinateDocId buildMaxCoordinate(float rangeInKm, int x, int y, int z, int docid) {
        int rangeInUnits = Conversions.radiusMetersToIntegerUnits(rangeInKm * 1000.0);
        int maxX = Conversions.calculateMaximumCoordinate(x, rangeInUnits);
        int maxY = Conversions.calculateMaximumCoordinate(y, rangeInUnits);
        int maxZ = Conversions.calculateMaximumCoordinate(z, rangeInUnits);
        return new CartesianCoordinateDocId(maxX, maxY, maxZ, docid);
    }
    
    
    /**
     * {@inheritDoc}
     */
    @Override
    public float score() throws IOException {
        assert docid >= 0 && docid != NO_MORE_DOCS;

        return score(currentDoc.getValue());
    }
    
    /**
     * MINIMUM_DISTANCE_WE_CARE_ABOUT = (x-x')*(x-x') + (y-y')*(y-y') + (z-z')*(z-z')
     *  Where x, y, z and x', y', z' are 0.0001f miles or 0.16 meters apart.
     * 
     */
    private static final float MINIMUM_DISTANCE_WE_CARE_ABOUT = 0.16f;
    private static final float MAX_DISTANCE_SQUARED = ((float)Conversions.EARTH_RADIUS_INTEGER_UNITS * Conversions.EARTH_RADIUS_INTEGER_UNITS * 4);
    
    
    private float score(Collection<GeRecordAndCartesianDocId> values) {
        float squaredDistance = MAX_DISTANCE_SQUARED;
        for (GeRecordAndCartesianDocId value : values) {
             float squaredDistance2 = CartesianComputeDistance.computeDistanceSquared(centroidX, centroidY, centroidZ, 
                     value.cartesianCoordinateDocId.x, value.cartesianCoordinateDocId.y, value.cartesianCoordinateDocId.z);
             if(squaredDistance2 < squaredDistance) {
                 squaredDistance = squaredDistance2;
             }
        }
        return score(squaredDistance);
    }
    
    /**
     * Score is 1/distance normalized to 1 at MINIMUM_DISTANCE_WE_CARE_ABOUT.
     * 
     * @param minimumDistanceMiles
     * @return
     */
    private float score(double squaredDistance) {
        double distance = Math.sqrt(squaredDistance);
        double distanceMeters = Conversions.unitsToMeters(distance);
        if (distanceMeters < MINIMUM_DISTANCE_WE_CARE_ABOUT) {
            return 1f;
        }
        
        return (float)(MINIMUM_DISTANCE_WE_CARE_ABOUT/distanceMeters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int advance(int target) throws IOException {
        assert (NO_MORE_DOCS == docid || DOCID_CURSOR_NONE_YET == docid || target >= docid);
        
        fillBlockContainingAndSeekTo(target);

        return docid;
    }
    
    private boolean doesCurrentTreeContain(int seekDocid) {
        if (currentSegment == null) {
            return false;
        }
        int maxDocAbsoluteCurrentPartition = startDocidOfCurrentPartition 
            + currentSegment.getMaxDoc();
        return seekDocid < maxDocAbsoluteCurrentPartition;
    }
    
    /**
     * 
     * @param seekDocid
     */
    private void seekToTree(int seekDocid) {
        while (!doesCurrentTreeContain(seekDocid)) {
            if (indexOfCurrentPartition == NO_MORE_DOCS) {
                return;
            } else if (indexOfCurrentPartition == DOCID_CURSOR_NONE_YET && segmentsInOrder.size() > 0) {
                indexOfCurrentPartition++;
                startDocidOfCurrentPartition = 0;
            } else {
                indexOfCurrentPartition++;
                if (indexOfCurrentPartition < segmentsInOrder.size()) {
                    startDocidOfCurrentPartition += segmentsInOrder.get(indexOfCurrentPartition-1).getMaxDoc();
                } else {
                    // we are past the end
                    indexOfCurrentPartition = NO_MORE_DOCS;
                    startDocidOfCurrentPartition = NO_MORE_DOCS;
                    docid = NO_MORE_DOCS;
                    return;
                }
            }
            currentSegment = segmentsInOrder.get(indexOfCurrentPartition);
        }

    }
    
    private boolean isBlockInMemoryAlreadyAndSeekWithinBlock(int seekDocid) {
        if (DOCID_CURSOR_NONE_YET == currentBlockGlobalMaxDoc) {
            return false;
        }
        if (seekDocid < currentBlockGlobalMaxDoc) {
            // it's possible its in the current block
            while (currentBlockScoredDocs.size() > 0 && docid < seekDocid && seekDocid < currentBlockGlobalMaxDoc) {
                nextDocidAndCurrentDocFromBlockInMemory();
            }
            if (seekDocid <= docid) {
                return true;
            }
        }

        return false;
    }

    private void nextDocidAndCurrentDocFromBlockInMemory() {
        Entry<Integer, Collection<GeRecordAndCartesianDocId>> doc = currentBlockScoredDocs.pollFirst();
        docid = doc.getKey() + startDocidOfCurrentPartition;
        // docid is now translated into the whole-index docid value
        currentDoc = doc;
    }
    
    private int currentBlockGlobalMaxDoc = DOCID_CURSOR_NONE_YET;
    
    private void fillBlockContainingAndSeekTo(int seekDocid) throws IOException {
        if (isBlockInMemoryAlreadyAndSeekWithinBlock(seekDocid)) {
            return;
        }
        
        if (DOCID_CURSOR_NONE_YET != currentBlockGlobalMaxDoc 
                && NO_MORE_DOCS != currentBlockGlobalMaxDoc) {
            // it was not found in the current block, 
            // so we should seek past the current block if not already doing so.
            seekDocid = Math.max(currentBlockGlobalMaxDoc, seekDocid);
        }
        seekToTree(seekDocid);
        
        if (NO_MORE_DOCS == docid) {
            return;
        }
        
        pullBlockInMemory(seekDocid);
        
        if (currentBlockScoredDocs.size() == 0) {
            fillBlockContainingAndSeekTo(currentBlockGlobalMaxDoc);
        } else if (NO_MORE_DOCS == docid) {
            return;
        } else {
            nextDocidAndCurrentDocFromBlockInMemory();
        }
    }
    
    private void pullBlockInMemory(int seekDocid) throws IOException {
        int offsetDocidWithinPartition = seekDocid - startDocidOfCurrentPartition;
        
        IDeletedDocs deletedDocsWithinSegment = new DeletedDocs(wholeIndexDeletedDocs, 
                startDocidOfCurrentPartition);
        int blockNumber = offsetDocidWithinPartition / BLOCK_SIZE;
        int minimumDocidInPartition = offsetDocidWithinPartition - blockNumber * BLOCK_SIZE;
        int maxDocInPartition = currentSegment.getMaxDoc();
        int maximumDocidInPartition = Math.min(maxDocInPartition, 
                (blockNumber + 1) * BLOCK_SIZE);
        currentBlockScoredDocs = geoBlockOfHitsProvider.getBlock(currentSegment, deletedDocsWithinSegment,
                cartesianBoundingBox[0], cartesianBoundingBox[1], cartesianBoundingBox[2],
                cartesianBoundingBox[3], cartesianBoundingBox[4], cartesianBoundingBox[5],
                minimumDocidInPartition, maximumDocidInPartition);
        currentBlockGlobalMaxDoc = startDocidOfCurrentPartition + maximumDocidInPartition;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int docID() {
        assert docid >= 0;

        return docid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int nextDoc() throws IOException {
        if (docid == NO_MORE_DOCS) {
            return NO_MORE_DOCS;
        }
        
        fillBlockContainingAndSeekTo(docid + 1);
        return docid;
    }
}
