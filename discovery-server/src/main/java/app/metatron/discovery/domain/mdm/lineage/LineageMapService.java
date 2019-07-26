/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.metatron.discovery.domain.mdm.lineage;

import app.metatron.discovery.domain.mdm.Metadata;
import app.metatron.discovery.domain.mdm.MetadataRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LineageMapService {

  private static Logger LOGGER = LoggerFactory.getLogger(LineageMapService.class);

  @Autowired
  LineageEdgeRepository edgeRepository;

  @Autowired
  MetadataRepository metadataRepository;

  public LineageMapService() {
  }

  void addNodeRecursive(LineageMap map, LineageNode node) {
    map.addNode(node.getDepth() + map.getDepthAdjustment(), node);
    map.visitedMetaIds.add(node.getMetaId());

    for (LineageNode child : node.getDownstreamMapNodes()) {
      if (map.visitedMetaIds.contains(child.getMetaId())) {
        continue;
      }
      addNodeRecursive(map, child);
    }
  }

  public LineageMap getLineageMap(String metaId) {
    LineageMap map = new LineageMap();  // depthLimit

    LineageNode topNode = getTopNode(map.getNeedEdges(), metaId);

    map.findOrigins(topNode, LineageMap.INITIAL_DEPTH);

    map.setDepthAdjustment();

    for (LineageNode node : map.origins) {
      addNodeRecursive(map, node);
    }

    return map;
  }

  private long getMinTier(List<LineageEdge> edges) {
    Long min = null;

    for (LineageEdge edge : edges) {
      if (edge.getTier() == null) {
        continue;
      } else if (min == null) {
        min = edge.getTier();
      } else if (edge.getTier() < min) {
        min = edge.getTier();
      }
    }

    return min;
  }

  private long getMaxTier(List<LineageEdge> edges) {
    Long max = null;

    for (LineageEdge edge : edges) {
      if (edge.getTier() == null) {
        continue;
      } else if (max == null) {
        max = edge.getTier();
      } else if (edge.getTier() > max) {
        max = edge.getTier();
      }
    }

    return max;
  }

  private void addMapNodeRecursive(List<LineageEdge> totalEdges, LineageNode node, boolean upward,
      List<String> visitedMetaIds, int depth, Long lastTier) {
    String metaId = node.getMetaId();
    List<LineageEdge> edges;
    LineageNode newNode;

    // Edges that have A as downstream are upstream edges of A, vice versa.
    if (upward) {
      edges = edgeRepository.findByDownstreamMetaId(metaId);
    } else {
      edges = edgeRepository.findByUpstreamMetaId(metaId);
    }

    // Once started upward, we only find upstreams, and upstreams of upstreams, and so on.
    // We are not interested the downstreams of any upstreams. Vice versa.
    for (LineageEdge edge : edges) {
      if (upward) {
        if (lastTier == null) {
          if (edge.getTier() != null) {
            lastTier = edge.getTier();
          }
        } else if (edge.getTier() != null && edge.getTier() > lastTier) {
          continue;
        }
        String upstreamMetaName = getMetaName(edge.getUpstreamMetaId());
        newNode = new LineageNode(edge.getUpstreamMetaId(), upstreamMetaName, depth + 1);
        node.getUpstreamMapNodes().add(newNode);
        newNode.getDownstreamMapNodes().add(node);
      } else {
        if (lastTier == null) {
          if (edge.getTier() != null) {
            lastTier = edge.getTier();
          }
        } else if (edge.getTier() != null && edge.getTier() < lastTier) {
          continue;
        }
        String downstreamMetaName = getMetaName(edge.getDownstreamMetaId());
        newNode = new LineageNode(edge.getDownstreamMetaId(), downstreamMetaName, depth + 1);
        node.getDownstreamMapNodes().add(newNode);
        newNode.getUpstreamMapNodes().add(node);
      }

      totalEdges.add(edge);

      if (visitedMetaIds.contains(newNode.getMetaId())) {
        newNode.setCircuit(true);
        continue;
      } else {
        visitedMetaIds.add(newNode.getMetaId());
      }

      Long nextTier = upward ? getMinTier(edges) : getMaxTier(edges);
      addMapNodeRecursive(totalEdges, newNode, upward, visitedMetaIds, depth + 1, nextTier);
    }
  }

  private String getMetaName(String metaId) {
    List<Metadata> metadatas = metadataRepository.findById(metaId);
    return metadatas.get(0).getName();
  }

  public LineageNode getTopNode(List<LineageEdge> totalEdges, String metaId) {
    LOGGER.trace("getTopNode(): start");

    List<String> visitedMetaIds = new ArrayList();
    visitedMetaIds.add(metaId);

    String metaName = getMetaName(metaId);
    LineageNode topNode = new LineageNode(metaId, metaName, 0);

    addMapNodeRecursive(totalEdges, topNode, true, visitedMetaIds, 0, null);
    addMapNodeRecursive(totalEdges, topNode, false, visitedMetaIds, 0, getMaxTier(totalEdges));

    LOGGER.trace("getTopNode(): end");
    return topNode;
  }

}
