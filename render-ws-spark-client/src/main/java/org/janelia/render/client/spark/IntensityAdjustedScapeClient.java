package org.janelia.render.client.spark;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Interval;

import static org.janelia.render.client.intensityadjust.IntensityAdjustedScapeClient.renderIntensityAdjustedScape;

/**
 * Spark client for rendering intensity adjusted montage scapes for a range of layers within a stack.
 *
 * @author Eric Trautman
 */
public class IntensityAdjustedScapeClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final IntensityAdjustParameters parameters = new IntensityAdjustParameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final IntensityAdjustedScapeClient client = new IntensityAdjustedScapeClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final IntensityAdjustParameters parameters;

    private IntensityAdjustedScapeClient(final IntensityAdjustParameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("IntensityAdjustedScapeClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();

        LOG.info("run: appId is {}", sparkAppId);

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.layerRange.minZ,
                                                                      parameters.layerRange.maxZ,
                                                                      parameters.zValues);
        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final File sectionRootDirectory = parameters.getSectionRootDirectory(new Date());
        FileUtil.ensureWritableDirectory(sectionRootDirectory);

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        final String slicePathFormatSpec = parameters.getSlicePathFormatSpec(stackMetaData,
                                                                             sectionRootDirectory);

        final JavaRDD<Double> rddSectionData = sparkContext.parallelize(zValues);

        final Function<Double, Integer> generateScapeFunction =
                z -> {

                    final int integralZ = z.intValue();

                    LogUtilities.setupExecutorLog4j("z " + integralZ);

                    // NOTE: need to create interval here since it is not labelled as Serializable
                    final Interval interval = RenderTools.stackBounds(stackMetaData);

                    renderIntensityAdjustedScape(parameters.renderWeb.getDataClient(),
                                                 parameters.stack,
                                                 interval,
                                                 parameters.correctionMethod,
                                                 slicePathFormatSpec,
                                                 parameters.format,
                                                 z.intValue());
                    return 1;
                };

        final JavaRDD<Integer> rddLayerCounts = rddSectionData.map(generateScapeFunction);

        final long processedLayerCount = rddLayerCounts.collect().stream().reduce(0, Integer::sum);

        LOG.info("run: collected stats");
        LOG.info("run: generated images for {} layers", processedLayerCount);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityAdjustedScapeClient.class);
}
