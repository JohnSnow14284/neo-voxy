package me.cortex.voxy.client.core;

import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

public class RenderPipelineFactory {
   public static AbstractRenderPipeline createPipeline(
      RenderProperties properties,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      AbstractRenderPipeline pipeline = null;
      if (IrisUtil.IRIS_INSTALLED) {
         pipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
      }

      if (pipeline == null) {
         pipeline = new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
      }

      return pipeline;
   }

   private static AbstractRenderPipeline createIrisPipeline(
      RenderProperties properties,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      WorldRenderingPipeline irisPipe = Iris.getPipelineManager().getPipelineNullable();
      if (irisPipe == null) {
         return null;
      } else if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
         IrisVoxyRenderPipelineData pipeData = getVoxyPipeData.voxy$getPipelineData();
         if (pipeData == null) {
            return null;
         } else {
            Logger.info("Creating voxy iris render pipeline");

            try {
               return new IrisVoxyRenderPipeline(properties, pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception var9) {
               Logger.error("Failed to create iris render pipeline", var9);
               IrisUtil.disableIrisShaders();
               return null;
            }
         }
      } else {
         return null;
      }
   }
}
