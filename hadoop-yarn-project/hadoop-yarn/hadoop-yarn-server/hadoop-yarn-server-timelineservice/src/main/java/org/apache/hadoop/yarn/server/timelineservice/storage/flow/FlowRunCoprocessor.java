/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.timelineservice.storage.flow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimestampGenerator;

/**
 * Coprocessor for flow run table.
 */
public class FlowRunCoprocessor extends BaseRegionObserver {

  private static final Log LOG = LogFactory.getLog(FlowRunCoprocessor.class);
  private boolean isFlowRunRegion = false;

  private Region region;
  /**
   * generate a timestamp that is unique per row in a region this is per region.
   */
  private final TimestampGenerator timestampGenerator =
      new TimestampGenerator();

  @Override
  public void start(CoprocessorEnvironment e) throws IOException {
    if (e instanceof RegionCoprocessorEnvironment) {
      RegionCoprocessorEnvironment env = (RegionCoprocessorEnvironment) e;
      this.region = env.getRegion();
      isFlowRunRegion = TimelineStorageUtils.isFlowRunTable(
          region.getRegionInfo(), env.getConfiguration());
    }
  }

  public boolean isFlowRunRegion() {
    return isFlowRunRegion;
  }

  /*
   * (non-Javadoc)
   *
   * This method adds the tags onto the cells in the Put. It is presumed that
   * all the cells in one Put have the same set of Tags. The existing cell
   * timestamp is overwritten for non-metric cells and each such cell gets a new
   * unique timestamp generated by {@link TimestampGenerator}
   *
   * @see
   * org.apache.hadoop.hbase.coprocessor.BaseRegionObserver#prePut(org.apache
   * .hadoop.hbase.coprocessor.ObserverContext,
   * org.apache.hadoop.hbase.client.Put,
   * org.apache.hadoop.hbase.regionserver.wal.WALEdit,
   * org.apache.hadoop.hbase.client.Durability)
   */
  @Override
  public void prePut(ObserverContext<RegionCoprocessorEnvironment> e, Put put,
      WALEdit edit, Durability durability) throws IOException {
    Map<String, byte[]> attributes = put.getAttributesMap();

    if (!isFlowRunRegion) {
      return;
    }
    // Assumption is that all the cells in a put are the same operation.
    List<Tag> tags = new ArrayList<>();
    if ((attributes != null) && (attributes.size() > 0)) {
      for (Map.Entry<String, byte[]> attribute : attributes.entrySet()) {
        Tag t = TimelineStorageUtils.getTagFromAttribute(attribute);
        tags.add(t);
      }
      byte[] tagByteArray = Tag.fromList(tags);
      NavigableMap<byte[], List<Cell>> newFamilyMap = new TreeMap<>(
          Bytes.BYTES_COMPARATOR);
      for (Map.Entry<byte[], List<Cell>> entry : put.getFamilyCellMap()
          .entrySet()) {
        List<Cell> newCells = new ArrayList<>(entry.getValue().size());
        for (Cell cell : entry.getValue()) {
          // for each cell in the put add the tags
          // Assumption is that all the cells in
          // one put are the same operation
          // also, get a unique cell timestamp for non-metric cells
          // this way we don't inadvertently overwrite cell versions
          long cellTimestamp = getCellTimestamp(cell.getTimestamp(), tags);
          newCells.add(CellUtil.createCell(CellUtil.cloneRow(cell),
              CellUtil.cloneFamily(cell), CellUtil.cloneQualifier(cell),
              cellTimestamp, KeyValue.Type.Put, CellUtil.cloneValue(cell),
              tagByteArray));
        }
        newFamilyMap.put(entry.getKey(), newCells);
      } // for each entry
      // Update the family map for the Put
      put.setFamilyCellMap(newFamilyMap);
    }
  }

  /**
   * Determines if the current cell's timestamp is to be used or a new unique
   * cell timestamp is to be used. The reason this is done is to inadvertently
   * overwrite cells when writes come in very fast. But for metric cells, the
   * cell timestamp signifies the metric timestamp. Hence we don't want to
   * overwrite it.
   *
   * @param timestamp
   * @param tags
   * @return cell timestamp
   */
  private long getCellTimestamp(long timestamp, List<Tag> tags) {
    // if ts not set (hbase sets to HConstants.LATEST_TIMESTAMP by default)
    // then use the generator
    if (timestamp == HConstants.LATEST_TIMESTAMP) {
      return timestampGenerator.getUniqueTimestamp();
    } else {
      return timestamp;
    }
  }

  /*
   * (non-Javadoc)
   *
   * Creates a {@link FlowScanner} Scan so that it can correctly process the
   * contents of {@link FlowRunTable}.
   *
   * @see
   * org.apache.hadoop.hbase.coprocessor.BaseRegionObserver#preGetOp(org.apache
   * .hadoop.hbase.coprocessor.ObserverContext,
   * org.apache.hadoop.hbase.client.Get, java.util.List)
   */
  @Override
  public void preGetOp(ObserverContext<RegionCoprocessorEnvironment> e,
      Get get, List<Cell> results) throws IOException {
    if (!isFlowRunRegion) {
      return;
    }

    Scan scan = new Scan(get);
    scan.setMaxVersions();
    RegionScanner scanner = null;
    try {
      scanner = new FlowScanner(e.getEnvironment(), scan,
          region.getScanner(scan), FlowScannerOperation.READ);
      scanner.next(results);
      e.bypass();
    } finally {
      if (scanner != null) {
        scanner.close();
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * Ensures that max versions are set for the Scan so that metrics can be
   * correctly aggregated and min/max can be correctly determined.
   *
   * @see
   * org.apache.hadoop.hbase.coprocessor.BaseRegionObserver#preScannerOpen(org
   * .apache.hadoop.hbase.coprocessor.ObserverContext,
   * org.apache.hadoop.hbase.client.Scan,
   * org.apache.hadoop.hbase.regionserver.RegionScanner)
   */
  @Override
  public RegionScanner preScannerOpen(
      ObserverContext<RegionCoprocessorEnvironment> e, Scan scan,
      RegionScanner scanner) throws IOException {

    if (isFlowRunRegion) {
      // set max versions for scan to see all
      // versions to aggregate for metrics
      scan.setMaxVersions();
    }
    return scanner;
  }

  /*
   * (non-Javadoc)
   *
   * Creates a {@link FlowScanner} Scan so that it can correctly process the
   * contents of {@link FlowRunTable}.
   *
   * @see
   * org.apache.hadoop.hbase.coprocessor.BaseRegionObserver#postScannerOpen(
   * org.apache.hadoop.hbase.coprocessor.ObserverContext,
   * org.apache.hadoop.hbase.client.Scan,
   * org.apache.hadoop.hbase.regionserver.RegionScanner)
   */
  @Override
  public RegionScanner postScannerOpen(
      ObserverContext<RegionCoprocessorEnvironment> e, Scan scan,
      RegionScanner scanner) throws IOException {
    if (!isFlowRunRegion) {
      return scanner;
    }
    return new FlowScanner(e.getEnvironment(), scan,
        scanner, FlowScannerOperation.READ);
  }

  @Override
  public InternalScanner preFlush(
      ObserverContext<RegionCoprocessorEnvironment> c, Store store,
      InternalScanner scanner) throws IOException {
    if (!isFlowRunRegion) {
      return scanner;
    }
    if (LOG.isDebugEnabled()) {
      if (store != null) {
        LOG.debug("preFlush store = " + store.getColumnFamilyName()
            + " flushableSize=" + store.getFlushableSize()
            + " flushedCellsCount=" + store.getFlushedCellsCount()
            + " compactedCellsCount=" + store.getCompactedCellsCount()
            + " majorCompactedCellsCount="
            + store.getMajorCompactedCellsCount() + " memstoreFlushSize="
            + store.getMemstoreFlushSize() + " memstoreSize="
            + store.getMemStoreSize() + " size=" + store.getSize()
            + " storeFilesCount=" + store.getStorefilesCount());
      }
    }
    return new FlowScanner(c.getEnvironment(), scanner,
        FlowScannerOperation.FLUSH);
  }

  @Override
  public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, StoreFile resultFile) {
    if (!isFlowRunRegion) {
      return;
    }
    if (LOG.isDebugEnabled()) {
      if (store != null) {
        LOG.debug("postFlush store = " + store.getColumnFamilyName()
            + " flushableSize=" + store.getFlushableSize()
            + " flushedCellsCount=" + store.getFlushedCellsCount()
            + " compactedCellsCount=" + store.getCompactedCellsCount()
            + " majorCompactedCellsCount="
            + store.getMajorCompactedCellsCount() + " memstoreFlushSize="
            + store.getMemstoreFlushSize() + " memstoreSize="
            + store.getMemStoreSize() + " size=" + store.getSize()
            + " storeFilesCount=" + store.getStorefilesCount());
      }
    }
  }

  @Override
  public InternalScanner preCompact(
      ObserverContext<RegionCoprocessorEnvironment> e, Store store,
      InternalScanner scanner, ScanType scanType, CompactionRequest request)
      throws IOException {

    if (!isFlowRunRegion) {
      return scanner;
    }
    FlowScannerOperation requestOp = FlowScannerOperation.MINOR_COMPACTION;
    if (request != null) {
      requestOp = (request.isMajor() ? FlowScannerOperation.MAJOR_COMPACTION
          : FlowScannerOperation.MINOR_COMPACTION);
      LOG.info("Compactionrequest= " + request.toString() + " "
          + requestOp.toString() + " RegionName=" + e.getEnvironment()
              .getRegion().getRegionInfo().getRegionNameAsString());
    }
    return new FlowScanner(e.getEnvironment(), scanner, requestOp);
  }
}
